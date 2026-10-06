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

# "--index NAME" embeds into another index's schema, so use that index's connections file:
# default_db2_NAME.xml, whose rgdRagDataSource URL ends in ?currentSchema=NAME,public.
SPRING_CONFIG=$APPDIR/../properties/default_db2.xml
ARGS=("$@")
for ((i = 0; i < ${#ARGS[@]}; i++)); do
    if [ "${ARGS[$i]}" = "--index" ]; then
        SPRING_CONFIG=$APPDIR/../properties/default_db2_${ARGS[$((i + 1))]}.xml
    fi
done
if [ ! -f "$SPRING_CONFIG" ]; then
    echo "connections file not found: $SPRING_CONFIG" >&2
    exit 1
fi

# Raw JVM stdout/stderr capture (crash diagnosis only, not emailed). The per-mode
# summaries are written by log4j: run.log (generate) and logs/embedRun.log (embed).
java -Dspring.config=$SPRING_CONFIG \
    -Dlog4j.configurationFile=file://$APPDIR/properties/log4j2.xml \
    -jar lib/$APPNAME.jar "$@" > logs/console.log 2>&1

# Email the summary for whichever mode ran: embed -> embedRun.log, generate -> run.log.
case "$*" in
    *"--mode embed"*) MAILLOG=$APPDIR/logs/embedRun.log ;;
    *)                MAILLOG=$APPDIR/run.log ;;
esac

mailx -s "[$SERVER] RGD AI Chatbot Rag loader" $EMAILLIST < $MAILLOG
