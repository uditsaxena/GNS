#
java -ea -cp ../../dist/GNS.jar -Djavax.net.ssl.trustStorePassword=qwerty -Djavax.net.ssl.trustStore=../../conf/GNS/conf/trustStore/node100.jks -Djavax.net.ssl.keyStorePassword=qwerty -Djavax.net.ssl.keyStore=/Users/westy/Documents/Code/GNS/conf/keyStore/node100.jks edu.umass.cs.gns.newApp.AppReconfigurableNode -test -nsfile ../../conf/single-server-info -configFile ns_debug.conf  &
# > NSlog 2>&1 &
java -ea -cp ../../dist/GNS.jar -Djavax.net.ssl.trustStorePassword=qwerty -Djavax.net.ssl.trustStore=../../conf/trustStore/node100.jks edu.umass.cs.gns.localnameserver.LocalNameServer -nsfile ../../conf/single-server-info -configFile lns.conf &
# > LNSlog 2>&1 &
# -Djavax.net.debug=ssl
