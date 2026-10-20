#!/usr/bin/env bash
#
# rgd-ai-chatbot-rag-pipeline
# Generate markdown for the pages of the RGD WordPress site (help, tutorials, tools, news).
# Pages are read from the WordPress REST API; see websitePageGenerator in AppConfigure.xml for
# the minimum word count and the exclude patterns.
./run.sh --mode generate --type website "$@"
