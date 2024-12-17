# ESPLoader
This is a partial Java port of the ESP loader from 
https://github.com/espressif/esptool

I also got it to run on Android for my Rocket altimeter application
https://github.com/bdureau/BearConsole2/tree/master/app/src/main/java/com/altimeter/bdureau/bearconsole/Flash

Currently this has been successfully been tested on 

ESP32

ESP32 WROOM

TTGO

LILYGO T-QT

ESP32S3

ESP32C3 

ESP8266

If you want to make it works for other chips check the following web page to modify the protocol
https://docs.espressif.com/projects/esptool/en/latest/esp32s3/advanced-topics/serial-protocol.html
select the chip you are using to see which codes you need to send
I do not have all the chips to test them, drop me a note if you need help

I have provided some test firmwares that can be flashed to your board


