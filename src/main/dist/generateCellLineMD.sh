#!/usr/bin/env bash
#
# rgd-ai-chatbot-rag-pipeline
# Generate cell line report markdown for one species (default rat). Pass --species <key> for another;
# human has ~124K Cellosaurus cell lines, so generate it only if it is wanted in the index.
./run.sh --mode generate --type cellline --species 3 "$@"
