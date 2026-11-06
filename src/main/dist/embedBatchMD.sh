#!/usr/bin/env bash
#
# rgd-ai-chatbot-rag-pipeline
# Embed through the OpenAI Batch API (asynchronous, within 24 hours, discounted) instead of
# live requests. Same result as the embed*.sh scripts: only new or changed chunks are bought.
# The run stays up until every batch it queued has been collected and written; if it is
# stopped, rerunning resumes the queued batches (data/batch) rather than paying again.
#
# Cell lines are embedded for rat only; pass other arguments through to both runs
# (e.g. --force). Ontology terms from OBO files are not included: run ./embedObo.sh.
./run.sh --mode batch-embed --path gene,qtl,strain,reference,pathway,expectedrange,website "$@"
./run.sh --mode batch-embed --path cellline --species 3 "$@"
