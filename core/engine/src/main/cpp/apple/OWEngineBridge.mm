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

#import "OWEngineBridge.h"

#include "engine_session.h"

namespace {
NSError * makeError(NSInteger code, const std::string & message) {
    return [NSError
        errorWithDomain:@"io.github.alpharomercoma.openweights.engine"
                   code:code
               userInfo:@{NSLocalizedDescriptionKey : [NSString stringWithUTF8String:message.c_str()]}];
}
}  // namespace

@implementation OWChatMessage

- (instancetype)initWithRole:(NSString *)role content:(NSString *)content {
    return [self initWithRole:role content:content toolCallID:nil];
}

- (instancetype)initWithRole:(NSString *)role
                      content:(NSString *)content
                   toolCallID:(nullable NSString *)toolCallID {
    self = [super init];
    if (self == nil) return nil;
    _role = [role copy];
    _content = [content copy];
    _toolCallID = [toolCallID copy];
    return self;
}

@end

@implementation OWToolDefinition

- (instancetype)initWithName:(NSString *)name
              toolDescription:(NSString *)toolDescription
               parametersJSON:(NSString *)parametersJSON {
    self = [super init];
    if (self == nil) return nil;
    _name = [name copy];
    _toolDescription = [toolDescription copy];
    _parametersJSON = [parametersJSON copy];
    return self;
}

@end

@interface OWToolCall ()
- (instancetype)initWithCall:(const openweights::ToolCall &)call;
@end

@implementation OWToolCall

- (instancetype)initWithCall:(const openweights::ToolCall &)call {
    self = [super init];
    if (self == nil) return nil;
    _callID = [NSString stringWithUTF8String:call.id.c_str()];
    _name = [NSString stringWithUTF8String:call.name.c_str()];
    _argumentsJSON = [NSString stringWithUTF8String:call.arguments_json.c_str()];
    return self;
}

@end

@interface OWGenerationStats ()
- (instancetype)initWithStats:(const openweights::GenerationStats &)stats;
@end

@implementation OWGenerationStats

- (instancetype)initWithStats:(const openweights::GenerationStats &)stats {
    self = [super init];
    if (self == nil) return nil;
    _promptTokens = stats.prompt_tokens;
    _cachedTokens = stats.cached_tokens;
    _generatedTokens = stats.generated_tokens;
    _prefillMillis = stats.prefill_ms;
    _decodeMillis = stats.decode_ms;
    return self;
}

@end

@implementation OWEngineSession {
    openweights::Session * _session;
}

@synthesize lastStats = _lastStats;
@synthesize lastToolCalls = _lastToolCalls;

- (nullable instancetype)initWithModelPath:(NSString *)modelPath
                                contextSize:(int32_t)contextSize
                                      error:(NSError **)error {
    self = [super init];
    if (self == nil) return nil;
    _lastToolCalls = @[];

    std::string loadError;
    // Threads, GPU layers and mmap all default to the same "let the caller decide later"
    // values the Android bridge uses at first load: 4/4/0/false. `set_threads` and a
    // reload are how those actually get tuned; this initializer is not where that belongs.
    _session = openweights::Session::load(
        std::string([modelPath UTF8String]),
        std::string(),
        contextSize,
        4,
        4,
        0,
        false,
        loadError);

    if (_session == nullptr) {
        if (error != nil) *error = makeError(1, loadError);
        return nil;
    }
    return self;
}

- (void)dealloc {
    delete _session;
}

- (nullable NSString *)generateWithMessages:(NSArray<OWChatMessage *> *)messages
                                       tools:(NSArray<OWToolDefinition *> *)tools
                                 temperature:(float)temperature
                                        topP:(float)topP
                                        topK:(int32_t)topK
                                   maxTokens:(int32_t)maxTokens
                                     onToken:(void (^)(NSString * piece))onToken
                                       error:(NSError **)error {
    std::vector<openweights::ChatMessage> cppMessages;
    cppMessages.reserve(messages.count);
    for (OWChatMessage * message in messages) {
        openweights::ChatMessage cppMessage;
        cppMessage.role = std::string([message.role UTF8String]);
        cppMessage.content = std::string([message.content UTF8String]);
        if (message.toolCallID != nil) {
            cppMessage.tool_call_id = std::string([message.toolCallID UTF8String]);
        }
        cppMessages.push_back(cppMessage);
    }

    std::vector<openweights::ToolDefinition> cppTools;
    cppTools.reserve(tools.count);
    for (OWToolDefinition * tool in tools) {
        openweights::ToolDefinition cppTool;
        cppTool.name = std::string([tool.name UTF8String]);
        cppTool.description = std::string([tool.toolDescription UTF8String]);
        cppTool.parameters_json = std::string([tool.parametersJSON UTF8String]);
        cppTools.push_back(cppTool);
    }

    openweights::SamplerConfig sampler;
    sampler.temperature = temperature;
    sampler.top_p = topP;
    sampler.top_k = topK;
    sampler.max_tokens = maxTokens;
    openweights::ReasoningConfig reasoning;
    reasoning.enabled = false;

    openweights::GenerationStats stats;
    openweights::ParsedReply reply;
    std::string generateError;

    const auto reason = _session->generate(
        cppMessages,
        cppTools,
        sampler,
        reasoning,
        [onToken](const char * piece) -> bool {
            if (onToken != nil) onToken([NSString stringWithUTF8String:piece]);
            return true;
        },
        stats,
        reply,
        generateError);

    _lastStats = [[OWGenerationStats alloc] initWithStats:stats];

    NSMutableArray<OWToolCall *> * toolCalls = [NSMutableArray arrayWithCapacity:reply.tool_calls.size()];
    for (const auto & call : reply.tool_calls) {
        [toolCalls addObject:[[OWToolCall alloc] initWithCall:call]];
    }
    _lastToolCalls = toolCalls;

    if (reason == openweights::StopReason::ERROR) {
        if (error != nil) *error = makeError(2, generateError);
        return nil;
    }
    return [NSString stringWithUTF8String:reply.content.c_str()];
}

- (BOOL)supportsTools {
    return _session->supports_tools() ? YES : NO;
}

- (void)reset {
    _session->reset();
}

- (void)cancel {
    _session->cancel();
}

@end
