@echo off
cd ../maven
:: 默认输出到上层results
mvn -B test "-Djunit.groups=load" "-Djunit.excludedGroups=" "-Dtrademe.jmeterHome=C:\Java\apache-jmeter-5.6.3"
pause
