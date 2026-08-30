// Does the MediaTek NPU beat the CPU at the one thing that matters for prefill?
//
// The CPU's rate is known: 131 t/s prefill on LFM2.5-1.2B-Q4_K_M, measured with
// llama-bench on this phone. A 1.17B model does about 2*1.17e9 = 2.34 GFLOP per
// prefilled token, so the CPU is sustaining roughly 307 GOP/s with 4-bit weights.
//
// The NPU cannot have 4-bit weights: NeuronAdapter's narrowest tensor type is
// eight bits. So this asks the fair version of the question — at int8, the best
// the NPU is allowed, does it clear the bar the CPU already reaches at int4?
//
// One FULLY_CONNECTED of the shape an FFN projection actually has.
#include "NeuronAdapter.h"

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

namespace {

// A prefill chunk against one FFN projection of a 1.2B model.
constexpr uint32_t kTokens = 1;   // rows: tokens in flight
constexpr uint32_t kIn     = 2048;  // embedding width
constexpr uint32_t kOut    = 8192;  // FFN width
constexpr int kIterations  = 20;

double MillisSince(std::chrono::steady_clock::time_point start) {
    return std::chrono::duration<double, std::milli>(
               std::chrono::steady_clock::now() - start)
        .count();
}

#define CHECK(call)                                                       \
    do {                                                                  \
        int status = (call);                                              \
        if (status != NEURON_NO_ERROR) {                                  \
            std::printf("FAILED %s -> %d\n", #call, status);              \
            return 1;                                                     \
        }                                                                 \
    } while (0)

}  // namespace

// Chosen at run time so the same binary answers both halves of the question:
// int8 is the narrowest the NPU has, f16 is what an unquantised model would use.
static bool gUseFloat16 = false;

int main(int argc, char** argv) {
    gUseFloat16 = (argc > 1 && std::strcmp(argv[1], "f16") == 0);
    // Unbuffered: the interesting failures here are aborts inside the vendor
    // library, and a buffered stdout loses every line that would say where.
    std::setvbuf(stdout, nullptr, _IONBF, 0);
    std::printf("shape: [%u x %u] @ [%u x %u] %s, %d iterations\n",
                kTokens, kIn, kIn, kOut, gUseFloat16 ? "f16" : "int8", kIterations);

    std::printf("step: model_create\n");
    NeuronModel* model = nullptr;
    CHECK(NeuronModel_create(&model));

    const uint32_t inputDims[2]  = {kTokens, kIn};
    const uint32_t weightDims[2] = {kOut, kIn};   // FULLY_CONNECTED: [units, in]
    const uint32_t biasDims[1]   = {kOut};
    const uint32_t outputDims[2] = {kTokens, kOut};

    // Scales chosen so the bias scale is the product the API requires.
    const float inputScale  = 0.02f;
    const float weightScale = 0.01f;

    NeuronOperandType input{};
    input.type = gUseFloat16 ? NEURON_TENSOR_FLOAT16 : NEURON_TENSOR_QUANT8_ASYMM;
    input.dimensionCount = 2;
    input.dimensions = inputDims;
    input.scale = gUseFloat16 ? 0.0f : inputScale;
    input.zeroPoint = gUseFloat16 ? 0 : 128;

    NeuronOperandType weights = input;
    weights.dimensions = weightDims;
    weights.scale = gUseFloat16 ? 0.0f : weightScale;

    // A float graph takes a float bias; a quantised one takes int32 scaled by
    // the product of the input and weight scales, which the API requires.
    NeuronOperandType bias{};
    bias.type = gUseFloat16 ? NEURON_TENSOR_FLOAT16 : NEURON_TENSOR_INT32;
    bias.dimensionCount = 1;
    bias.dimensions = biasDims;
    bias.scale = gUseFloat16 ? 0.0f : inputScale * weightScale;
    bias.zeroPoint = 0;

    NeuronOperandType fuse{};
    fuse.type = NEURON_INT32;

    NeuronOperandType output = input;
    output.dimensions = outputDims;
    output.scale = gUseFloat16 ? 0.0f : 0.05f;

    CHECK(NeuronModel_addOperand(model, &input));    // 0
    CHECK(NeuronModel_addOperand(model, &weights));  // 1
    CHECK(NeuronModel_addOperand(model, &bias));     // 2
    CHECK(NeuronModel_addOperand(model, &fuse));     // 3
    CHECK(NeuronModel_addOperand(model, &output));   // 4

    // Weights and bias are constants baked into the graph, as they would be for
    // a real model: the NPU compiles them in and they never cross the boundary
    // at execution time. Only the activations move per call.
    const size_t elementBytes = gUseFloat16 ? 2 : 1;
    std::vector<uint8_t> weightData(static_cast<size_t>(kOut) * kIn * elementBytes, 0x30);
    std::vector<uint8_t> biasData(static_cast<size_t>(kOut) * (gUseFloat16 ? 2 : 4), 0);
    const int32_t fuseNone = NEURON_FUSED_NONE;

    CHECK(NeuronModel_setOperandValue(model, 1, weightData.data(), weightData.size()));
    CHECK(NeuronModel_setOperandValue(model, 2, biasData.data(), biasData.size()));
    CHECK(NeuronModel_setOperandValue(model, 3, &fuseNone, sizeof(fuseNone)));

    const uint32_t operationInputs[4] = {0, 1, 2, 3};
    const uint32_t operationOutputs[1] = {4};
    CHECK(NeuronModel_addOperation(model, NEURON_FULLY_CONNECTED, 4, operationInputs,
                                   1, operationOutputs));

    const uint32_t modelInputs[1] = {0};
    const uint32_t modelOutputs[1] = {4};
    CHECK(NeuronModel_identifyInputsAndOutputs(model, 1, modelInputs, 1, modelOutputs));
    std::printf("step: model_finish\n");
    CHECK(NeuronModel_finish(model));

    // Compilation is the ahead-of-time step, and its cost is a finding in its
    // own right: a ggml backend would pay it again for every new shape, and a
    // KV cache changes shape on every token.
    // Pinned to the MDLA rather than left to the default, which is not a
    // refinement but the difference between a result and a crash: the default
    // considers every device, and this phone's GPU/MVPU path fails to dlopen
    // OpenCL and aborts inside the adapter. The MDLA is the NPU proper and the
    // only one this question is about.
    std::printf("step: enumerate devices\n");
    uint32_t deviceCount = 0;
    CHECK(Neuron_getDeviceCount(&deviceCount));
    const NeuronDevice* mdla = nullptr;
    for (uint32_t i = 0; i < deviceCount; ++i) {
        NeuronDevice* device = nullptr;
        const char* name = "";
        if (Neuron_getDevice(i, &device) != NEURON_NO_ERROR) continue;
        if (NeuronDevice_getName(device, &name) != NEURON_NO_ERROR) continue;
        if (std::strstr(name, "mdla") != nullptr) {
            mdla = device;
            std::printf("device: %s\n", name);
        }
    }
    if (mdla == nullptr) {
        std::printf("no MDLA device on this phone\n");
        return 1;
    }

    NeuronCompilation* compilation = nullptr;
    CHECK(NeuronCompilation_createForDevices(model, &mdla, 1, &compilation));
    CHECK(NeuronCompilation_setPreference(compilation, NEURON_PREFER_SUSTAINED_SPEED));
    std::printf("step: compile\n");
    auto compileStart = std::chrono::steady_clock::now();
    CHECK(NeuronCompilation_finish(compilation));
    std::printf("compile: %.1f ms\n", MillisSince(compileStart));

    NeuronExecution* execution = nullptr;
    CHECK(NeuronExecution_create(compilation, &execution));

    std::vector<uint8_t> activations(static_cast<size_t>(kTokens) * kIn * elementBytes, 0x30);
    std::vector<uint8_t> result(static_cast<size_t>(kTokens) * kOut * elementBytes, 0);

    std::vector<double> samples;
    samples.reserve(kIterations);
    for (int i = 0; i < kIterations; ++i) {
        CHECK(NeuronExecution_setInput(execution, 0, nullptr, activations.data(),
                                       activations.size()));
        CHECK(NeuronExecution_setOutput(execution, 0, nullptr, result.data(),
                                        result.size()));
        auto start = std::chrono::steady_clock::now();
        CHECK(NeuronExecution_compute(execution));
        samples.push_back(MillisSince(start));
    }

    std::sort(samples.begin(), samples.end());
    const double best = samples.front();
    const double median = samples[samples.size() / 2];
    // Two operations per multiply-accumulate.
    const double ops = 2.0 * kTokens * kIn * kOut;
    std::printf("execute: best %.2f ms, median %.2f ms\n", best, median);
    std::printf("throughput: best %.1f GOP/s, median %.1f GOP/s\n",
                ops / (best * 1e6), ops / (median * 1e6));
    std::printf("\nCPU reference on this phone: ~307 GOP/s at int4 (131 t/s prefill)\n");
    std::printf("verdict: NPU int8 %s the CPU's int4 rate\n",
                (ops / (best * 1e6)) > 307.0 ? "BEATS" : "does NOT beat");
    return 0;
}
