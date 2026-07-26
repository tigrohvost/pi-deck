package dev.pideck.app.core;

import java.util.List;

public final class ModelCatalog {
    private static final long GIB = 1_073_741_824L;

    public static final ModelSpec NANO = new ModelSpec(
            "qwen3.5-0.8b",
            "Qwen3.5 0.8B",
            "NANO",
            "ggml-org/Qwen3.5-0.8B-GGUF",
            "8fea620810c4afa23dd6443f999a48574c1611a3",
            "Qwen3.5-0.8B-Q4_0.gguf",
            563_036_064L,
            "57d1997790d1744fba5b40a7317df71ea5e2acee28c47e78f0cce39c0703f8cf",
            3,
            "Самый быстрый профиль для телефонов с 3–4 GB RAM."
    );

    public static final ModelSpec EDGE = new ModelSpec(
            "qwen3.5-2b",
            "Qwen3.5 2B",
            "EDGE",
            "bartowski/Qwen_Qwen3.5-2B-GGUF",
            "7d26695454df6de5fbcce2e58681e62dae06ce43",
            "Qwen_Qwen3.5-2B-Q4_K_M.gguf",
            1_396_198_496L,
            "57a1085840f497d764a7fc5d346922dbde961efb54cc792ea81d694fd846a1d8",
            6,
            "Лёгкий агент: Python, небольшие правки и быстрые ответы."
    );

    public static final ModelSpec CORE = new ModelSpec(
            "qwen3.5-4b",
            "Qwen3.5 4B",
            "CORE",
            "bartowski/Qwen_Qwen3.5-4B-GGUF",
            "4168f45a16a1290d65a4ec0fa312ae917a4c15d6",
            "Qwen_Qwen3.5-4B-Q4_K_M.gguf",
            3_013_027_808L,
            "13c16f426047e2de38cd075bdade4a7bcbc8c774384876f677740cda65f8a983",
            8,
            "Лучший баланс для современного телефона и основной профиль PI//DECK."
    );

    public static final ModelSpec MAX = new ModelSpec(
            "qwen3.5-9b",
            "Qwen3.5 9B",
            "MAX",
            "bartowski/Qwen_Qwen3.5-9B-GGUF",
            "182be2fd6c7bc44887d88a91cb03ff009cc9f549",
            "Qwen_Qwen3.5-9B-Q4_K_M.gguf",
            6_169_341_984L,
            "d784ce9eda1a5a7b51e8f705a9e6310844bf4f173654d115823c775fdea56d43",
            16,
            "Сильнее в многошаговой работе; требует флагманской памяти и терпения."
    );

    private static final List<ModelSpec> ALL = List.of(NANO, EDGE, CORE, MAX);

    private ModelCatalog() {
    }

    public static List<ModelSpec> all() {
        return ALL;
    }

    public static ModelSpec byId(String id) {
        if (id != null) {
            for (ModelSpec model : ALL) {
                if (model.id.equals(id)) return model;
            }
        }
        return EDGE;
    }

    public static ModelSpec recommend(long totalRamBytes, long freeStorageBytes) {
        ModelSpec desired;
        if (totalRamBytes >= 14L * GIB) {
            desired = MAX;
        } else if (totalRamBytes >= 7L * GIB) {
            desired = CORE;
        } else if (totalRamBytes >= 5L * GIB) {
            desired = EDGE;
        } else {
            desired = NANO;
        }

        int index = ALL.indexOf(desired);
        while (index > 0 && requiredStorage(ALL.get(index)) > freeStorageBytes) {
            index--;
        }
        return ALL.get(index);
    }

    public static long requiredStorage(ModelSpec model) {
        return model.bytes + Math.max(256L * 1_048_576L, model.bytes / 10L);
    }
}
