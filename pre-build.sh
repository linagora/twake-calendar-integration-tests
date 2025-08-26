#!/bin/bash

docker build -t sabre-v3-it -f Dockerfile.sabre3 .
docker build -t sabre-v4-it -f Dockerfile.sabre4 .
docker build -t tcalendar-it -f Dockerfile.tcalendar .
docker build -t ldap-it -f Dockerfile.ldap .