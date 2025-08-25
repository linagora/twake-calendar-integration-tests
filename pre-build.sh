#!/bin/bash

docker build -t sabre-it -f Dockerfile.sabre .
docker build -t tcalendar-it -f Dockerfile.tcalendar .