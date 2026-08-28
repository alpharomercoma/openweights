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

@implementation OWEngineSession {
    openweights::Session * _session;
}

- (nullable instancetype)initWithModelPath:(NSString *)modelPath
                                contextSize:(int32_t)contextSize
                                      error:(NSError **)error {
    self = [super init];
    if (self == nil) return nil;

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

- (nullable NSString *)generateWithPrompt:(NSString *)prompt
                                 maxTokens:(int32_t)maxTokens
                                   onToken:(void (^)(NSString * piece))onToken
                                     error:(NSError **)error {
    std::vector<openweights::ChatMessage> messages;
    openweights::ChatMessage message;
    message.role = "user";
    message.content = std::string([prompt UTF8String]);
    messages.push_back(message);

    openweights::SamplerConfig sampler;
    sampler.max_tokens = maxTokens;
    openweights::ReasoningConfig reasoning;
    reasoning.enabled = false;

    openweights::GenerationStats stats;
    openweights::ParsedReply reply;
    std::string generateError;

    const auto reason = _session->generate(
        messages,
        {},
        sampler,
        reasoning,
        [onToken](const char * piece) -> bool {
            if (onToken != nil) onToken([NSString stringWithUTF8String:piece]);
            return true;
        },
        stats,
        reply,
        generateError);

    if (reason == openweights::StopReason::ERROR) {
        if (error != nil) *error = makeError(2, generateError);
        return nil;
    }
    return [NSString stringWithUTF8String:reply.content.c_str()];
}

- (void)reset {
    _session->reset();
}

- (void)cancel {
    _session->cancel();
}

@end
