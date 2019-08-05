#!/usr/bin/env bash

NAME="$1"

if [ "${NAME}" = "--help" ] || [ "${NAME}" = "-h" ]
then
	echo "USAGE: ./create-jar.sh [JARNAME[.jar]]"
	exit 1
elif [ -z "${NAME}" ]
then
	NAME="Mars4_5-SV.jar"
fi

if [ "${NAME:(-4)}" != ".jar" ]
then
	NAME="${NAME}.jar"
fi

jar cmf mainclass.txt ${NAME} PseudoOps.txt Config.properties Syscall.properties Settings.properties MARSlicense.txt mainclass.txt MipsXRayOpcode.xml registerDatapath.xml controlDatapath.xml ALUcontrolDatapath.xml CreateMarsJar.bat Mars.java Mars.class docs help images mars 
