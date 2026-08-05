#!/usr/bin/env bash
#
# rgd-ai-chatbot-rag-pipeline
# Re-embeds every gene file unconditionally (--force), whether or not it changed.
# Use embedMD.sh to embed only new or changed files.
./run.sh --mode embed --path gene --force
