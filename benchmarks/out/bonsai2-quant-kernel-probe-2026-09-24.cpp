#include "ggml.h"
#include "ggml-cpu.h"
#include "ggml-quants.h"
#include "ggml-impl.h"
#include "simd-mappings.h"
#include <arm_neon.h>
#include "quants.h"
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstdint>
#include <cerrno>
#include <sched.h>
#include <thread>
#include <vector>

template<int A>
static void ptq1_neon_accumulate(int n, float * GGML_RESTRICT s, size_t bs, const void * GGML_RESTRICT vx, size_t bx, const void * GGML_RESTRICT vy, size_t by, int nrc) {
    const int qk = QK_PTQ1_0;
    const int nb = n / qk;

    assert(n % qk == 0);
    assert(nrc == 1);
    GGML_UNUSED(nrc);
    GGML_UNUSED(bx);
    GGML_UNUSED(by);
    GGML_UNUSED(bs);

    const block_ptq1_0 * GGML_RESTRICT x = static_cast<const block_ptq1_0 *>(vx);
    const block_q8_0   * GGML_RESTRICT y = static_cast<const block_q8_0 *>(vy);

    static const uint8_t pow3[6] = {1, 3, 9, 27, 81, 243};
    static const size_t  stages[3] = {32, 16, 8};

    float32x4_t sumv[A];
    for (auto & v : sumv) v = vdupq_n_f32(0.0f);

    for (int i = 0; i < nb; i++) {
        int8_t q[QK_PTQ1_0];
        int o = 0;

        size_t j = 0;
        for (size_t st = 0; st < 3; ++st) {
            const size_t c = stages[st];
            for (; j + c <= sizeof(x->qs); j += c) {
                for (size_t nn = 0; nn < 5; ++nn) {
                    for (size_t m = 0; m < c; ++m) {
                        const uint8_t v  = x[i].qs[j + m] * pow3[nn];
                        const int16_t xi = ((uint16_t) v * 3) >> 8;
                        q[o++] = (int8_t) (xi - 1);
                    }
                }
            }
        }
        for (size_t nn = 0; nn < 4; ++nn) {
            for (size_t h = 0; h < sizeof(x->qh); ++h) {
                const uint8_t v  = x[i].qh[h] * pow3[nn];
                const int16_t xi = ((uint16_t) v * 3) >> 8;
                q[o++] = (int8_t) (xi - 1);
            }
        }
        assert(o == QK_PTQ1_0);

        const float d0 = GGML_CPU_FP16_TO_FP32(x[i].d);
        for (int k = 0; k < 4; ++k) {
            const block_q8_0 * yb = &y[i * 4 + k];
            const float scale = d0 * GGML_CPU_FP16_TO_FP32(yb->d);
            int32x4_t dot = vdotq_s32(vdupq_n_s32(0), vld1q_s8(q + k * 32), vld1q_s8(yb->qs));
            dot = vdotq_s32(dot, vld1q_s8(q + k * 32 + 16), vld1q_s8(yb->qs + 16));
            sumv[k % A] = vmlaq_n_f32(sumv[k % A], vcvtq_f32_s32(dot), scale);
        }
    }

    for (int j = 1; j < A; ++j) sumv[0] = vaddq_f32(sumv[0], sumv[j]);
    *s = vaddvq_f32(sumv[0]);
}

using Clock = std::chrono::steady_clock;
struct Kernel {
    const char * name;
    ggml_type weight;
    ggml_type activation;
    ggml_vec_dot_t dot;
    std::vector<uint8_t> weights;
    std::vector<uint8_t> activations;
    size_t stride;
};

static uint32_t rng = 123456789;
static uint32_t next_random() {
    rng ^= rng << 13; rng ^= rng >> 17; rng ^= rng << 5;
    return rng;
}

int main(int argc, char ** argv) {
    const int n = argc > 1 ? std::atoi(argv[1]) : 5120;
    const int rows = argc > 2 ? std::atoi(argv[2]) : 8192;
    const int core = argc > 3 ? std::atoi(argv[3]) : 7;
    if (n <= 0 || n % 256 || n > 32768 || rows < 1 || rows > 16384) return 2;
    if (core < 0 || core > 7) return 2;
    cpu_set_t requested, actual_mask;
    CPU_ZERO(&requested);
    CPU_ZERO(&actual_mask);
    CPU_SET(core, &requested);
    int attempt = 0;
    for (; attempt < 20; ++attempt) {
        if (sched_setaffinity(0, sizeof(requested), &requested) == 0) break;
        if (errno != EINVAL) break;
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
    if (sched_getaffinity(0, sizeof(actual_mask), &actual_mask) != 0 || !CPU_EQUAL(&requested, &actual_mask)) {
        std::fprintf(stderr, "Could not pin CPU %d after %d attempts, current CPU %d, errno %d\n", core, attempt + 1, sched_getcpu(), errno);
        return 6;
    }
    std::fprintf(stderr, "CPU %d, affinity attempts %d\n", sched_getcpu(), attempt + 1);
    ggml_init_params init = { 1 << 20, nullptr, true };
    ggml_context * ctx = ggml_init(init);
    if (!ctx) return 3;
    Kernel kernels[] = {
        {"ptq1_q8_0", GGML_TYPE_PTQ1_0, GGML_TYPE_Q8_0, ggml_vec_dot_ptq1_0_q8_0, {}, {}, 0},
        {"pq2_q8_K", GGML_TYPE_PQ2_0, GGML_TYPE_Q8_K, ggml_vec_dot_pq2_0_q8_K, {}, {}, 0},
        {"pq2_q8_0_arm", GGML_TYPE_PQ2_0, GGML_TYPE_Q8_0, ggml_vec_dot_pq2_0_q8_0, {}, {}, 0},
        {"ptq1_neon_accumulate", GGML_TYPE_PTQ1_0, GGML_TYPE_Q8_0, ptq1_neon_accumulate<1>, {}, {}, 0},
        {"ptq1_neon_accumulate2", GGML_TYPE_PTQ1_0, GGML_TYPE_Q8_0, ptq1_neon_accumulate<2>, {}, {}, 0},
        {"ptq1_neon_accumulate4", GGML_TYPE_PTQ1_0, GGML_TYPE_Q8_0, ptq1_neon_accumulate<4>, {}, {}, 0},
    };
    std::vector<float> input(n), activation(n), reference_weight(n), reference_activation(n);
    for (int j = 0; j < n; ++j) activation[j] = (int(next_random() % 20001) - 10000) / 3000.0f;
    for (auto & kernel : kernels) {
        kernel.stride = ggml_row_size(kernel.weight, n);
        kernel.weights.resize(kernel.stride * rows);
        kernel.activations.resize(ggml_row_size(kernel.activation, n));
        ggml_get_type_traits_cpu(kernel.activation)->from_float(activation.data(), kernel.activations.data(), n);
    }
    double max_normalized_error = 0;
    int checked = 0;
    for (int row = 0; row < rows; ++row) {
        for (int j = 0; j < n; ++j) {
            const float scale = std::ldexp(1.0f, ((row + j / 128) % 8) - 4);
            input[j] = row == 0 ? 0.0f : (int(next_random() % 3) - 1) * scale;
        }
        for (auto & kernel : kernels) {
            auto * weight = kernel.weights.data() + row * kernel.stride;
            ggml_get_type_traits(kernel.weight)->from_float_ref(input.data(), weight, n);
            if (row < 32 || row == rows - 1) {
                ggml_get_type_traits(kernel.weight)->to_float(weight, reference_weight.data(), n);
                if (kernel.activation == GGML_TYPE_Q8_K) {
                    dequantize_row_q8_K(reinterpret_cast<const block_q8_K *>(kernel.activations.data()), reference_activation.data(), n);
                } else {
                    ggml_get_type_traits(kernel.activation)->to_float(kernel.activations.data(), reference_activation.data(), n);
                }
                double reference = 0, l1 = 0;
                for (int j = 0; j < n; ++j) {
                    if (reference_weight[j] != input[j]) {
                        std::fprintf(stderr, "Weight conversion differs: %s row=%d column=%d\n", kernel.name, row, j);
                        return 4;
                    }
                    const double product = double(reference_weight[j]) * reference_activation[j];
                    reference += product; l1 += std::abs(product);
                }
                float actual = 0;
                kernel.dot(n, &actual, 0, weight, 0, kernel.activations.data(), 0, 1);
                const double error = std::abs(actual - reference) / std::max(1.0, l1);
                max_normalized_error = std::max(max_normalized_error, error);
                if (!std::isfinite(actual) || error > 3e-6) {
                    std::fprintf(stderr, "Dot differs: %s row=%d actual=%g reference=%g error=%g\n", kernel.name, row, actual, reference, error);
                    return 5;
                }
                ++checked;
            }
        }
    }
    std::printf("{\"kind\":\"correctness\",\"width\":%d,\"rows\":%d,\"checkedDots\":%d,\"maxErrorNormalizedByL1\":%.12g}\n", n, rows, checked, max_normalized_error);
    std::fflush(stdout);
    volatile double sink = 0;
    for (int repetition = 0; repetition < 3; ++repetition) {
        for (int offset = 0; offset < 6; ++offset) {
            auto & kernel = kernels[(offset + repetition) % 6];
            const auto start = Clock::now();
            uint64_t dots = 0;
            double seconds = 0;
            do {
                double checksum = 0;
                for (int row = 0; row < rows; ++row) {
                    float result;
                    kernel.dot(n, &result, 0, kernel.weights.data() + row * kernel.stride, 0, kernel.activations.data(), 0, 1);
                    checksum += result;
                }
                sink = checksum;
                dots += rows;
                seconds = std::chrono::duration<double>(Clock::now() - start).count();
            } while (seconds < 0.3);
            std::printf("{\"kind\":\"timing\",\"kernel\":\"%s\",\"width\":%d,\"rows\":%d,\"weightBytes\":%zu,\"repetition\":%d,\"dots\":%llu,\"seconds\":%.9f,\"nsPerWeight\":%.9f,\"checksum\":%.9g}\n",
                kernel.name, n, rows, kernel.weights.size(), repetition, (unsigned long long)dots, seconds, seconds * 1e9 / (dots * n), double(sink));
            std::fflush(stdout);
        }
    }
    ggml_free(ctx);
}
