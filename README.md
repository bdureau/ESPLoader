# ESPLoader
This is a partial Java port of the ESP loader from 
https://github.com/espressif/esptool

To my knowledge this is the only Java port of the esptool

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

ESP32S2

ESP32H2

ESP32C5 

ESP32C6 

ESP32 C2 needs to be tested waitting for Espressif to release it on the Arduino environement

If you want to make it works for other chips check the following web page to modify the protocol
https://docs.espressif.com/projects/esptool/en/latest/esp32s3/advanced-topics/serial-protocol.html
select the chip you are using to see which codes you need to send
I do not have all the chips to test them, drop me a note if you need help


I have provided some test firmwares that can be flashed to your board


# Contributing

If you want to contribute to the project just fork my project or send me some code. 

Report any issue or bug that you have

Suggestions and enhancement are welcome

The code is free for you to download and you do not need to buy anything from me. However it cost money to try out new boards, you need to buy them and test them them so if you want to financially help me you can donate via paypal

| Paypal | 
| ------ |
| [![](https://www.paypalobjects.com/en_US/i/btn/btn_donateCC_LG.gif)](https://www.paypal.com/paypalme/bearaltimeter) | 

# Disclaimer

I am not responsible for any damage that could hapen. The code is provided as it is
