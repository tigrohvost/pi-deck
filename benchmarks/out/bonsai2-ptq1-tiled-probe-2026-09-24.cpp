#include "ggml.h"
#include "ggml-cpu.h"
#include "ggml-quants.h"
#include "ggml-impl.h"
#include "simd-mappings.h"
#include "quants.h"
#include <arm_neon.h>
#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstdint>
#include <sched.h>
#include <string>
#include <vector>

static uint32_t random_state = 0x8b72e39f;
static uint32_t next_random() {
    random_state ^= random_state << 13;
    random_state ^= random_state >> 17;
    random_state ^= random_state << 5;
    return random_state;
}

// Decode each PTQ block once, then apply it to 2-8 Q8 activation rows.
static void tiled_dot(int n, const block_ptq1_0 * x, const block_q8_0 * y,
                      size_t y_stride, int tile, float * out) {
    static const uint8_t pow3[5] = {1, 3, 9, 27, 81};
    static const size_t stages[3] = {32, 16, 8};
    std::array<float, 8> sums{};
    for (int i = 0; i < n / QK_PTQ1_0; ++i) {
        int8_t q[QK_PTQ1_0];
        int o = 0;
        size_t j = 0;
        for (size_t st = 0; st < 3; ++st) {
            const size_t c = stages[st];
            for (; j + c <= sizeof(x->qs); j += c) {
                for (size_t nn = 0; nn < 5; ++nn) {
                    for (size_t m = 0; m < c; ++m) {
                        const uint8_t v = x[i].qs[j + m] * pow3[nn];
                        q[o++] = (int8_t) ((((uint16_t) v * 3) >> 8) - 1);
                    }
                }
            }
        }
        for (size_t nn = 0; nn < 4; ++nn) {
            for (size_t h = 0; h < sizeof(x->qh); ++h) {
                const uint8_t v = x[i].qh[h] * pow3[nn];
                q[o++] = (int8_t) ((((uint16_t) v * 3) >> 8) - 1);
            }
        }
        if (o != QK_PTQ1_0) std::abort();

        const float d0 = GGML_CPU_FP16_TO_FP32(x[i].d);
        for (int t = 0; t < tile; ++t) {
            float block_sum = 0;
            const block_q8_0 * yt = (const block_q8_0 *) ((const char *) y + t * y_stride) + i * 4;
            for (int k = 0; k < 4; ++k) {
                const int8x16_t q0 = vld1q_s8(q + k * 32);
                const int8x16_t q1 = vld1q_s8(q + k * 32 + 16);
                int32x4_t dot = vdotq_s32(vdupq_n_s32(0), q0, vld1q_s8(yt[k].qs));
                dot = vdotq_s32(dot, q1, vld1q_s8(yt[k].qs + 16));
                block_sum += GGML_CPU_FP16_TO_FP32(yt[k].d) * vaddvq_s32(dot);
            }
            sums[t] += d0 * block_sum;
        }
    }
    for (int t = 0; t < tile; ++t) out[t] = sums[t];
}

using Clock = std::chrono::steady_clock;

int main(int argc, char ** argv) {
    const int n = argc > 1 ? std::atoi(argv[1]) : 5120;
    const int rows = argc > 2 ? std::atoi(argv[2]) : 8192;
    const int core = argc > 3 ? std::atoi(argv[3]) : 3;
    if (n < 128 || n % 128 || rows < 1 || rows > 16384 || core < 0 || core > 7) return 2;
    cpu_set_t mask;
    CPU_ZERO(&mask);
    CPU_SET(core, &mask);
    if (sched_setaffinity(0, sizeof(mask), &mask) != 0) return 3;

    const size_t weight_stride = n / 128 * sizeof(block_ptq1_0);
    const size_t activation_stride = n / 32 * sizeof(block_q8_0);
    std::vector<block_ptq1_0> weights(rows * n / 128);
    std::vector<block_q8_0> activations(8 * n / 32);
    for (auto & block : weights) {
        block.d = GGML_CPU_FP32_TO_FP16((next_random() % 8 + 1) / 8.0f);
        for (auto & value : block.qs) value = next_random() % 243;
        for (auto & value : block.qh) value = next_random() % 81;
    }
    for (auto & block : activations) {
        block.d = GGML_CPU_FP32_TO_FP16((next_random() % 8 + 1) / 32.0f);
        for (auto & value : block.qs) value = int(next_random() % 255) - 127;
    }

    if (argc > 4 && std::string(argv[4]) == "check-nrc2") {
        double max_error = 0;
        for (int row = 0; row + 1 < std::min(rows, 64); row += 2) {
            float actual[32] = {};
            ggml_vec_dot_ptq1_0_q8_0(n, actual, 16,
                (const char *) weights.data() + row * weight_stride, weight_stride,
                activations.data(), activation_stride, 2);
            for (int t = 0; t < 2; ++t) {
                for (int w = 0; w < 2; ++w) {
                    float expected = 0;
                    ggml_vec_dot_ptq1_0_q8_0(n, &expected, 0,
                        (const char *) weights.data() + (row + w) * weight_stride, 0,
                        (const char *) activations.data() + t * activation_stride, 0, 1);
                    max_error = std::max(max_error, std::abs(double(actual[t * 16 + w]) - expected)
                        / std::max(1.0, std::abs(double(expected))));
                }
            }
        }
        std::printf("{\"kind\":\"nrc2-correctness\",\"width\":%d,\"maxRelativeError\":%.9g}\n", n, max_error);
        return max_error <= 1e-4 ? 0 : 4;
    }

    std::vector<float> results(rows * 8);
    volatile double sink = 0;
    for (int tile : {2, 4, 8}) {
        double max_error = 0;
        for (int row = 0; row < std::min(rows, 64); ++row) {
            float actual[8];
            tiled_dot(n, (const block_ptq1_0 *) ((const char *) weights.data() + row * weight_stride),
                      activations.data(), activation_stride, tile, actual);
            for (int t = 0; t < tile; ++t) {
                float expected = 0;
                ggml_vec_dot_ptq1_0_q8_0(n, &expected, 0,
                    (const char *) weights.data() + row * weight_stride, 0,
                    (const char *) activations.data() + t * activation_stride, 0, 1);
                max_error = std::max(max_error, std::abs(double(actual[t]) - expected)
                    / std::max(1.0, std::abs(double(expected))));
            }
        }
        if (max_error > 1e-4) {
            std::fprintf(stderr, "correctness failed tile=%d error=%g\n", tile, max_error);
            return 4;
        }
        std::printf("{\"kind\":\"correctness\",\"tile\":%d,\"maxRelativeError\":%.9g}\n", tile, max_error);
        for (int repetition = 0; repetition < 4; ++repetition) {
            for (int order = 0; order < 2; ++order) {
                const bool optimized = (order + repetition) % 2 == 0;
                const auto start = Clock::now();
                int iterations = 0;
                double seconds;
                do {
                    for (int row = 0; row < rows; ++row) {
                        const void * x = (const char *) weights.data() + row * weight_stride;
                        float * result = results.data() + row * 8;
                        if (optimized) {
                            tiled_dot(n, (const block_ptq1_0 *) x, activations.data(),
                                      activation_stride, tile, result);
                        } else {
                            for (int t = 0; t < tile; ++t) {
                                ggml_vec_dot_ptq1_0_q8_0(n, &result[t], 0, x, 0,
                                    (const char *) activations.data() + t * activation_stride, 0, 1);
                            }
                        }
                    }
                    sink = results[(iterations % rows) * 8];
                    ++iterations;
                    seconds = std::chrono::duration<double>(Clock::now() - start).count();
                } while (seconds < 0.2);
                std::printf("{\"kind\":\"timing\",\"tile\":%d,\"optimized\":%s,\"repetition\":%d,\"rows\":%d,\"width\":%d,\"iterations\":%d,\"seconds\":%.9f,\"nsPerWeightToken\":%.9f}\n",
                    tile, optimized ? "true" : "false", repetition, rows, n, iterations,
                    seconds, seconds * 1e9 / (double(iterations) * rows * n * tile));
                std::fflush(stdout);
            }
        }
    }
    if (!std::isfinite(double(sink))) return 5;
}
