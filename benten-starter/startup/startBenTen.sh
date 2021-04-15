#!bin/bash

# Start BenTen Server only if it is not running
if [ “$(ps -ef | grep -v grep | grep mariadb | wc -l)” -le 0 ] 
then
 # Note starting mariadb not as a sudoer
 java -jar ./benten-starter-0.1.5.war
 echo "BenTen Started"
else
 echo "BenTen Already Running"
fi