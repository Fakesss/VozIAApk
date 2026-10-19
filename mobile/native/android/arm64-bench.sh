#!/usr/bin/env bash
# Speed of the pinned OmniVoice and llama.cpp on a real ARM64 CPU (a Linux arm64
# CI runner), built three ways: the generic ARMv8.0 build the app shipped first
# (no tinyBLAS), and the dotprod/fp16 and i8mm variants with tinyBLAS the app now
# picks on phones that have those features (see build.sh). Same sources, patches
# and models as the app.
#   arm64-bench.sh <out-dir>
set -euo pipefail
root="$(cd "$(dirname "$0")" && pwd)"
out=$(mkdir -p "$1" && cd "$1" && pwd)
[[ "$(uname -m)" == aarch64 ]] || { echo "Run on an arm64 Linux machine" >&2; exit 1; }
python3 "$root/fetch.py"
pins="$root/../pins.json"
omni_commit="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["omnivoice.cpp"]["source"]["commit"])' "$pins")"
llama_commit="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["llama.cpp"]["source"]["commit"])' "$pins")"
omni_source="$root/build/sources/omnivoice.cpp-$omni_commit"
llama_source="$root/build/sources/llama.cpp-$llama_commit"
grep -q vozia_step_hook "$omni_source/src/maskgit-tts.h" || patch -d "$omni_source" -p1 --forward --batch < "$root/patches/omnivoice-step-hook.patch"
grep -q ov_vozia_n_threads "$omni_source/src/backend.h" || patch -d "$omni_source" -p1 --forward --batch < "$root/patches/omnivoice-threads.patch"

# Models: the catalogue's pinned files, checked against its SHA-256.
models="$root/build/bench-models"; mkdir -p "$models"
python3 - "$root/../../contract/models.json" "$models" <<'EOF'
import hashlib, json, sys, urllib.request, pathlib
catalogue, target = json.load(open(sys.argv[1])), pathlib.Path(sys.argv[2])
for model in catalogue['models']:
    if model['id'] not in ('omnivoice-q4-k-m', 'qwen3-1-7b-q4-k-m'):
        continue
    for f in model['files']:
        path = target / f['path']
        if not path.exists():
            url = f"https://huggingface.co/{model['repo']}/resolve/{model['revision']}/{f['path']}"
            urllib.request.urlretrieve(url, path.with_suffix('.partial'))
            path.with_suffix('.partial').rename(path)
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        assert digest == f['sha256'], f"SHA-256 mismatch for {f['path']}"
        print('verified', f['path'])
EOF

threads=$(nproc)
summary="$out/summary.md"
{
    echo "## ARM64 CPU benchmark"
    echo
    echo "$(lscpu | grep -E 'Model name|^CPU\(s\)' | tr -s ' ' | paste -sd ';' -) · flags: $(grep -m1 Features /proc/cpuinfo | grep -oE 'asimddp|asimdhp|i8mm|bf16|sve2?' | sort -u | paste -sd ' ' -)"
    echo
    echo "| Build | OmniVoice: seconds for the clone (audio seconds) | Qwen3 1.7B prompt tok/s | Qwen3 1.7B generation tok/s |"
    echo "|---|---|---|---|"
} > "$summary"
printf '%s\n' "The phone records a short sentence like this one, and the app speaks it again with the same voice." > "$out/text.txt"
for variant in "first release (armv8-a):-DGGML_LLAMAFILE=OFF" "dotprod+fp16:-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16 -DGGML_LLAMAFILE=ON" "i8mm:-DGGML_CPU_ARM_ARCH=armv8.6-a+dotprod+fp16+i8mm -DGGML_LLAMAFILE=ON"; do
    name="${variant%%:*}"; read -r -a flags <<< "${variant#*:}"
    tag=$(echo "$name" | tr -cd 'a-z0-9')
    common=(-G Ninja -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_CPU_KLEIDIAI=OFF ${flags[@]+"${flags[@]}"})
    cmake -S "$omni_source" -B "$root/build/bench-omni-$tag" "${common[@]}" -DGGML_METAL=OFF -DGGML_BLAS=OFF > "$out/configure-omni-$tag.txt"
    cmake --build "$root/build/bench-omni-$tag" --target omnivoice-tts -j "$threads" > "$out/build-omni-$tag.txt"
    cmake -S "$llama_source" -B "$root/build/bench-llama-$tag" "${common[@]}" -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_TOOLS=ON -DLLAMA_BUILD_SERVER=OFF -DLLAMA_CURL=OFF > "$out/configure-llama-$tag.txt"
    cmake --build "$root/build/bench-llama-$tag" --target llama-bench -j "$threads" > "$out/build-llama-$tag.txt"
    omni=$(find "$root/build/bench-omni-$tag" -name omnivoice-tts -type f -perm -u+x | head -1)
    bench=$(find "$root/build/bench-llama-$tag" -name llama-bench -type f -perm -u+x | head -1)
    start=$(date +%s.%N)
    "$omni" --model "$models/omnivoice-base-Q4_K_M.gguf" --codec "$models/omnivoice-tokenizer-Q4_K_M.gguf" --no-fa \
        --ref-wav "$omni_source/examples/freeman.wav" --ref-text "$omni_source/examples/freeman.txt" --lang English \
        -o "$out/clone-$tag.wav" < "$out/text.txt" > "$out/omni-$tag.txt" 2>&1
    seconds=$(python3 -c "import sys;print(round(float(sys.argv[2])-float(sys.argv[1]),1))" "$start" "$(date +%s.%N)")
    audio=$(python3 -c "import wave,sys;w=wave.open(sys.argv[1]);print(round(w.getnframes()/w.getframerate(),1))" "$out/clone-$tag.wav")
    "$bench" -m "$models/Qwen3-1.7B-Q4_K_M.gguf" -p 128 -n 32 -t "$threads" -o json > "$out/llama-$tag.json" 2> "$out/llama-$tag.log"
    rates=$(python3 -c "
import json,sys
rows=json.load(open(sys.argv[1]))
pp=[r['avg_ts'] for r in rows if r['n_prompt']>0 and r['n_gen']==0]
tg=[r['avg_ts'] for r in rows if r['n_gen']>0 and r['n_prompt']==0]
print(f'{pp[0]:.1f} | {tg[0]:.1f}')" "$out/llama-$tag.json")
    echo "| $name | $seconds s ($audio s) | $rates |" >> "$summary"
done
echo >> "$summary"
echo "Threads: OmniVoice uses half the cores (its own default), llama-bench $threads. Phones run the same code on their big cores." >> "$summary"
cat "$summary"
