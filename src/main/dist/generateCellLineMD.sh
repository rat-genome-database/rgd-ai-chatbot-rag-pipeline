#!/usr/bin/env bash
#
# rgd-ai-chatbot-rag-pipeline
# Generate cell line report markdown for one species (default rat). Pass --species <key> for another.
# generateAllMD.sh generates every species.
./run.sh --mode generate --type cellline --species 3 "$@"
