#!/usr/bin/env bash
#
# rgd-ai-chatbot-rag-pipeline
#
. /etc/profile
APPNAME=rgd-ai-chatbot-rag-pipeline
SERVER=`hostname -s | tr '[a-z]' '[A-Z]'`

#EMAILLIST=mtutaj@mcw.edu,llamers@mcw.edu
EMAILLIST="mtutaj@mcw.edu llamers@mcw.edu"

APPDIR=/home/rgddata/pipelines/$APPNAME
cd $APPDIR

java -Dspring.config=$APPDIR/../properties/default_db2.xml \
    -Dlog4j.configurationFile=file://$APPDIR/properties/log4j2.xml \
    -jar lib/$APPNAME.jar "$@" > run.log 2>&1

mailx -s "[$SERVER] RGD AI Chatbot Rag loader" $EMAILLIST < $APPDIR/logs/summary.log
