/*
 * Copyright 2026 The OpenWeights Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/**
 * The subset of `openweights::GenerationStats` a chat screen needs to render
 * `↑input ↓output · CHn%` -- see `Telemetry.kt`'s `ContextMeter` on Android for the metric
 * this mirrors. Not a 1:1 struct mirror: `context_used`/`context_size`/`time_to_first_token_ms`
 * aren't wired to any UI yet, so they stay out until something reads them.
 */
@interface OWGenerationStats : NSObject
@property (nonatomic, readonly) int32_t promptTokens;
@property (nonatomic, readonly) int32_t cachedTokens;
@property (nonatomic, readonly) int32_t generatedTokens;
@property (nonatomic, readonly) int64_t prefillMillis;
@property (nonatomic, readonly) int64_t decodeMillis;
@end

/**
 * One turn of chat history. `Session::generate` re-renders the *whole* conversation through
 * the model's chat template on every call and relies on its own prefix-match against the KV
 * cache to skip re-decoding what it already has -- so unlike a naive "send just the new
 * message" API, callers here must pass the full transcript every time, same as
 * `LlamaCppEngine.kt` does on Android. That is also what makes `cachedTokens` meaningful:
 * it is the prefix `Session` recognized from the previous call, not something this bridge
 * tracks itself.
 */
@interface OWChatMessage : NSObject
@property (nonatomic, copy, readonly) NSString * role;
@property (nonatomic, copy, readonly) NSString * content;
/** Set on a `tool` role message: which call this is the result of. See `ChatMessage::tool_call_id`. */
@property (nonatomic, copy, readonly, nullable) NSString * toolCallID;
- (instancetype)initWithRole:(NSString *)role content:(NSString *)content;
- (instancetype)initWithRole:(NSString *)role
                      content:(NSString *)content
                   toolCallID:(nullable NSString *)toolCallID NS_DESIGNATED_INITIALIZER;
- (instancetype)init NS_UNAVAILABLE;
@end

/** A tool the model may call, described the OpenAI-schema way. See `ToolDefinition`. */
@interface OWToolDefinition : NSObject
@property (nonatomic, copy, readonly) NSString * name;
@property (nonatomic, copy, readonly) NSString * toolDescription;
/** JSON Schema for the arguments object. */
@property (nonatomic, copy, readonly) NSString * parametersJSON;
- (instancetype)initWithName:(NSString *)name
              toolDescription:(NSString *)toolDescription
               parametersJSON:(NSString *)parametersJSON;
@end

/** A call the model asked for, not yet executed. See `ToolCall`. */
@interface OWToolCall : NSObject
@property (nonatomic, copy, readonly) NSString * callID;
@property (nonatomic, copy, readonly) NSString * name;
/** Arguments as JSON, exactly as the model produced them. */
@property (nonatomic, copy, readonly) NSString * argumentsJSON;
@end

/**
 * The narrow boundary between Swift and `openweights::Session`.
 *
 * Deliberately not a 1:1 mirror of `Session`'s C++ API: no `std::vector`, `std::string` or
 * `std::function` crosses this header, because Swift cannot see any of them. Tool calling and
 * reasoning are still out of scope here -- those remain a separate follow-on effort, same as
 * on Android.
 */
@interface OWEngineSession : NSObject

/**
 * Loads a model synchronously. Blocks the calling thread for as long as the weights take
 * to read, same as the Android JNI bridge's `nativeLoadModel` does -- callers are expected
 * to hop off the main thread before calling this, not to expect it to do that for them.
 */
- (nullable instancetype)initWithModelPath:(NSString *)modelPath
                                contextSize:(int32_t)contextSize
                                      error:(NSError * _Nullable * _Nullable)error
    NS_DESIGNATED_INITIALIZER;

- (instancetype)init NS_UNAVAILABLE;

/**
 * Blocking generation over the full conversation so far. `onToken` fires on the calling
 * thread for each decoded piece, the same contract `Session::generate`'s own callback has;
 * there is no streaming back onto the main thread here -- that is Swift's
 * `AsyncThrowingStream` wrapper's job, not this bridge's. `temperature`/`topP`/`topK` mirror
 * `SamplerConfig`'s fields of the same name; `maxTokens` of 0 means "until end-of-turn or the
 * context fills," same as the C++ default.
 */
- (nullable NSString *)generateWithMessages:(NSArray<OWChatMessage *> *)messages
                                       tools:(NSArray<OWToolDefinition *> *)tools
                                 temperature:(float)temperature
                                        topP:(float)topP
                                        topK:(int32_t)topK
                                   maxTokens:(int32_t)maxTokens
                                     onToken:(void (^)(NSString * piece))onToken
                                       error:(NSError * _Nullable * _Nullable)error;

/** Stats for the most recently completed `generateWithMessages:...` call, or nil before one runs. */
@property (nonatomic, readonly, nullable) OWGenerationStats * lastStats;

/**
 * Tool calls the model asked for in the most recently completed `generateWithMessages:...`
 * call. Empty when the model answered directly instead of calling anything.
 */
@property (nonatomic, readonly) NSArray<OWToolCall *> * lastToolCalls;

/**
 * Whether the loaded model's chat template renders tool definitions at all. See
 * `Session::supports_tools`. A model whose template does not support tools was never told
 * about them by `common_chat_templates_apply` even when `tools` is non-empty, and free-runs
 * text that only looks like a tool call (no grammar constraining it) -- callers should check
 * this before offering tools, the same way `ChatViewModel.kt` does on Android.
 */
@property (nonatomic, readonly) BOOL supportsTools;

/** Drops the KV cache. See `Session::reset`. */
- (void)reset;

/** Safe to call from another thread while `generate` is running. See `Session::cancel`. */
- (void)cancel;

@end

NS_ASSUME_NONNULL_END
