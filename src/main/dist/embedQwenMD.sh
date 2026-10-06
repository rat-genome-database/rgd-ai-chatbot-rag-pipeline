#!/usr/bin/env bash
#
# rgd-ai-chatbot-rag-pipeline
# Embeds gene reports into the qwen index (qwen3-embedding:8b -> rgd_rag schema qwen).
# Embeds only new or changed files; add --force to re-embed every file unconditionally.
./run.sh --mode embed --index qwen --path gene "$@"
