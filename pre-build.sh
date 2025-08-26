#!/bin/bash

docker build -t sabre-it -f Dockerfile.sabre .
docker build -t tcalendar-it -f Dockerfile.tcalendar .
docker build -t ldap-it -f Dockerfile.ldap .