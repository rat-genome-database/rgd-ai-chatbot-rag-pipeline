#!/usr/bin/env bash
#
# rgd-ai-chatbot-rag-pipeline
# Generate one PhenoMiner expected-range document per clinical measurement (CMO accession).
./run.sh --mode generate --type expectedrange "$@"
