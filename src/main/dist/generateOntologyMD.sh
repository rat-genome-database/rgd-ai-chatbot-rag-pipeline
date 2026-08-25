#!/usr/bin/env bash
#
# rgd-ai-chatbot-rag-pipeline
# Generate ontology-term report markdown.
# Pass --ontology <ID> (e.g. MP, RDO, GO, PW) to restrict to one ontology;
# with no --ontology, every public ontology is generated.
./run.sh --mode generate --type ontology
