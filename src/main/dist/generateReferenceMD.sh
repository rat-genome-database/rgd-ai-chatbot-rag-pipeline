#!/usr/bin/env bash
#
# rgd-ai-chatbot-rag-pipeline
# Generate reference report markdown for every reference cited by an RGD-curated annotation
# (~64K, all species; written under reference/all).
./run.sh --mode generate --type reference "$@"
