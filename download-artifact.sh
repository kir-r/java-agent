#!/bin/bash

if [ "$AGENT_VERSION" = "latest" ]
then
      echo "Variable $AGENT_VERSION is latest"
      echo "Download agent latest version"
      echo "github_url is:"
      github_url=$(curl -s https://api.github.com/repos/Drill4J/java-agent/releases | jq '.[0].assets | .[].browser_download_url' | grep linux | sed 's/"//g')
      echo $github_url
      # Download
      wget -q -N $github_url
      ls
      unzip -o agent-linuxX64-*.zip
      ls
      cp -R linuxX64-*/* /data/agent/
      cp -R linuxX64-*/* /data/

else
      echo "Variable $AGENT_VERSION is NOT latest"
      echo "Download agent $AGENT_VERSION"
      AGENT_VERSION=$(echo $AGENT_VERSION | sed 's/v//')
      echo "github_url is:"
      github_url="https://github.com/Drill4J/java-agent/releases/download/v$AGENT_VERSION/agent-linuxX64-$AGENT_VERSION.zip"
      echo $github_url

      # Download
      wget -q -N $github_url
      unzip -o agent-linuxX64-$AGENT_VERSION.zip
      cp -R linuxX64-$AGENT_VERSION/* /data/agent/
      cp -R linuxX64-$AGENT_VERSION/* /data/
fi
