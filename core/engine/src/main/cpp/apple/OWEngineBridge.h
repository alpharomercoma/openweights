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
 * The narrow boundary between Swift and `openweights::Session`.
 *
 * Deliberately not a 1:1 mirror of `Session`'s C++ API: no `std::vector`, `std::string` or
 * `std::function` crosses this header, because Swift cannot see any of them. What's here is
 * the smallest slice that proves the real engine runs from Swift -- one user message in,
 * one plain-text reply out, no tools or reasoning yet. Those come with the vertical-slice
 * milestone this smoke test is the first step of, not this one.
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
 * Blocking, single-turn generation. `onToken` fires on the calling thread for each decoded
 * piece, the same contract `Session::generate`'s own callback has; there is no streaming
 * back onto the main thread here; that is Swift's `AsyncThrowingStream` wrapper's job, not
 * this bridge's.
 */
- (nullable NSString *)generateWithPrompt:(NSString *)prompt
                                 maxTokens:(int32_t)maxTokens
                                   onToken:(void (^)(NSString * piece))onToken
                                     error:(NSError * _Nullable * _Nullable)error;

/** Stats for the most recently completed `generateWithPrompt:...` call, or nil before one runs. */
@property (nonatomic, readonly, nullable) OWGenerationStats * lastStats;

/** Drops the KV cache. See `Session::reset`. */
- (void)reset;

/** Safe to call from another thread while `generate` is running. See `Session::cancel`. */
- (void)cancel;

@end

NS_ASSUME_NONNULL_END
