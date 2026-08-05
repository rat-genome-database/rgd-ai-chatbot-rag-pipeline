#!/usr/bin/env bash
#
# rgd-ai-chatbot-rag-pipeline
# Re-embeds every strain file unconditionally (--force), whether or not it changed.
# Use embedStrainMD.sh to embed only new or changed files.
./run.sh --mode embed --path strain --force
