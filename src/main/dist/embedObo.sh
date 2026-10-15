#!/usr/bin/env bash
#
# rgd-ai-chatbot-rag-pipeline
# Embeds ontology term reports built straight from the OBO files in oboDir
# (data/markdown/ontology/obo). Those files come from a separate pipeline, so there
# is no generate step: each term report is built in memory and embedded directly.
#
# Embeds only new or changed terms (chunks differ from what is stored).
# Add --force to re-embed every term unconditionally.
# Add --ontology DOID to restrict to one ontology.
./run.sh --mode embed --path obo "$@"
