#!/usr/bin/env bash
#
# rgd-ai-chatbot-rag-pipeline
# Generate a document for every curated pathway diagram page (keyed by PW accession).
./run.sh --mode generate --type pathway "$@"
