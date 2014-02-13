#!/bin/bash

target=$1

if [ -z $target ] ; then
	target="tests"
fi

server=$2
if [ -z $server ] ; then
	server="https://www-dev.lupapiste.fi"
fi

hubert='192.168.7.223'
bianca='192.168.7.253'

#remote=$bianca
remote=$hubert

pybot -d target --exclude integration --exclude fail --variable BROWSER:internetexplorer --variable SELENIUM:http://$remote:4444/wd/hub --variable SERVER:$server $target
