#!/usr/bin/env bash
# Publish the fork's Koog artifacts to the `gh-pages` branch (a GitHub Pages maven repository).
#
# Consumers resolve the `ai.koog` group from https://yezxx.github.io/koog, so run this after changing anything
# they depend on. Needs JDK 17 in JAVA_HOME and push access to https://github.com/yezxx/koog.
#
# Usage: scripts/publish-gh-pages.sh
set -euo pipefail

cd "$(dirname "$0")/.."

# Modules consumers resolve (directly or transitively). Same set as the local mavenLocal publish.
modules=(
    agents/agents-core
    agents/agents-tools
    agents/agents-utils
    agents/agents-ext
    agents/agents-features/agents-features-event-handler
    agents/agents-features/agents-features-memory
    agents/agents-features/agents-features-tokenizer
    rag/rag-base
    prompt/prompt-llm
    prompt/prompt-markdown
    prompt/prompt-model
    prompt/prompt-processor
    prompt/prompt-structure
    prompt/prompt-tokenizer
    prompt/prompt-executor/prompt-executor-clients
    prompt/prompt-executor/prompt-executor-model
    prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client
    prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client-base
    prompt/prompt-executor/prompt-executor-clients/prompt-executor-deepseek-client
    http-client/http-client-core
    http-client/http-client-ktor
    serialization/serialization-core
    utils
)

tasks=()
for module in "${modules[@]}"; do
    project=":${module//\//:}"
    for publication in KotlinMultiplatform LinuxX64 AndroidRelease IosArm64 IosSimulatorArm64; do
        tasks+=("$project:publish${publication}PublicationToArtifactsRepository")
    done
done

./gradlew --no-daemon --max-workers=2 "${tasks[@]}"

staging="$(mktemp -d)"
trap 'rm -rf "$staging"' EXIT
cp -a build/artifacts/maven/. "$staging/"
touch "$staging/.nojekyll"

cd "$staging"
git init -q
git checkout -q --orphan gh-pages
git add -A
git commit -q -m "Publish koog maven artifacts (linuxX64 / Android / iOS)"
git push -f https://github.com/yezxx/koog.git gh-pages

echo "Published: https://yezxx.github.io/koog"
