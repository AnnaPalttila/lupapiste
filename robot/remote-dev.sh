#!/bin/bash

target=$1

if [ -z $target ] ; then
	target="tests"
fi

hubert='192.168.7.223';
bianca='192.168.7.253'

remote=$bianca

pybot -d target --exclude integration --exclude fail --RunEmptySuite --variable SELENIUM:http://$remote:4444/wd/hub --variable SERVER:http://lupadev.solita.fi $target
