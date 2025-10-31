#!/bin/bash

# $1 Sabre v4 docker image to test against. Thought to integrate integration tests directly into Sabre CI.

docker build -t sabre-v3-it -f Dockerfile.sabre3 .

if [ -n "$1" ]; then
  echo "Using custom SABRE_4_IMAGE: $1"
  docker build --build-arg SABRE_4_IMAGE="$1" -t sabre-v4-it -f Dockerfile.sabre4 .
else
  echo "Using default SABRE_4_IMAGE from Dockerfile"
  docker build -t sabre-v4-it -f Dockerfile.sabre4 .
fi

docker build -t sabre-v4-7-it -f Dockerfile.sabre4_7 .

docker pull linagora/twake-calendar-side-service:branch-master
docker build -t tcalendar-it -f Dockerfile.tcalendar .
docker build -t ldap-it -f Dockerfile.ldap .
