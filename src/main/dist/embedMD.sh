#!/usr/bin/env bash
#
# rgd-ai-chatbot-rag-pipeline
# Embeds only new or changed files (chunks differ from what's stored).
# Add --force to re-embed every file unconditionally.
./run.sh --mode embed --path gene
