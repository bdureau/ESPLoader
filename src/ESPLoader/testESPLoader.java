package ESPLoader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import java.util.zip.Deflater;

import com.fazecast.jSerialComm.SerialPort;

public class testESPLoader {

	public static SerialPort comPort;

	public static int ESP_FLASH_BLOCK = 0x400;

	private static final int ESP_ROM_BAUD = 115200;
	private static final int FLASH_WRITE_SIZE = 0x400;
	private static final int STUBLOADER_FLASH_WRITE_SIZE = 0x4000;
	private static final int FLASH_SECTOR_SIZE = 0x1000; // Flash sector size, minimum unit of erase.

	private static final int CHIP_DETECT_MAGIC_REG_ADDR = 0x40001000;
	private static final int ESP8266 = 0x8266;
	private static final int ESP32 = 0x32;
	private static final int ESP32S2 = 0x3252;
	private static final int ESP32S3 = 0x3253;
	private static final int ESP32C3 = 0x32C3;
	private static final int ESP32_DATAREGVALUE = 0x15122500;
	private static final int ESP8266_DATAREGVALUE = 0x00062000;
	private static final int ESP32S2_DATAREGVALUE = 0x500;

	private static final int BOOTLOADER_FLASH_OFFSET = 0x1000;
	private static final int ESP_IMAGE_MAGIC = 0xe9;

	// Commands supported by ESP8266 ROM bootloader
	private static final byte ESP_FLASH_BEGIN = 0x02;
	private static final byte ESP_FLASH_DATA = 0x03;
	private static final byte ESP_FLASH_END = 0x04;
	private static final byte ESP_MEM_BEGIN = 0x05;
	private static final byte ESP_MEM_END = 0x06;
	private static final int ESP_MEM_DATA = 0x07;
	private static final byte ESP_SYNC = 0x08;
	private static final int ESP_WRITE_REG = 0x09;
	private static final byte ESP_READ_REG = 0x0A;

	// Some commands supported by ESP32 ROM bootloader (or -8266 w/ stub)
	private static final int ESP_SPI_SET_PARAMS = 0x0B; // 11
	private static final int ESP_SPI_ATTACH = 0x0D; // 13
	private static final int ESP_READ_FLASH_SLOW = 0x0E; // 14 // ROM only, much slower than the stub flash read
	private static final int ESP_CHANGE_BAUDRATE = 0x0F; // 15
	private static final int ESP_FLASH_DEFL_BEGIN = 0x10; // 16
	private static final int ESP_FLASH_DEFL_DATA = 0x11; // 17
	private static final int ESP_FLASH_DEFL_END = 0x12; // 18
	private static final int ESP_SPI_FLASH_MD5 = 0x13; // 19

	// Commands supported by ESP32-S2/S3/C3/C6 ROM bootloader only
	private static final int ESP_GET_SECURITY_INFO = 0x14;

	// Some commands supported by stub only
	private static final int ESP_ERASE_FLASH = 0xD0;
	private static final int ESP_ERASE_REGION = 0xD1;
	private static final int ESP_READ_FLASH = 0xD2;
	private static final int ESP_RUN_USER_CODE = 0xD3;

	// Response code(s) sent by ROM
	private static final int ROM_INVALID_RECV_MSG = 0x05;

	// Initial state for the checksum routine
	private static final byte ESP_CHECKSUM_MAGIC = (byte) 0xEF;

	private static final int UART_DATE_REG_ADDR = 0x60000078;

	private static final int USB_RAM_BLOCK = 0x800;
	private static final int ESP_RAM_BLOCK = 0x1800;

	// Timeouts
	private static final int DEFAULT_TIMEOUT = 3000;
	private static final int CHIP_ERASE_TIMEOUT = 120000; // timeout for full chip erase in ms
	private static final int MAX_TIMEOUT = CHIP_ERASE_TIMEOUT * 2; // longest any command can run in ms
	private static final int SYNC_TIMEOUT = 100; // timeout for syncing with bootloader in ms
	private static final int ERASE_REGION_TIMEOUT_PER_MB = 30000; // timeout (per megabyte) for erasing a region in ms
	private static final int MEM_END_ROM_TIMEOUT = 500;
	private static final int MD5_TIMEOUT_PER_MB = 8000;
	private static int chip;

	private static boolean IS_STUB = false;


	private static boolean DEBUG = false;
	static class cmdRet {
		int retCode;
		byte retValue[] = new byte[2048];
	}

	/*
	 * The following is a quick and dirty port to Java of the ESPTool from Espressif
	 * Systems This works only for the ESP32, ESP32S3, ESP32C3 and ESP8266 but can be modified to flash the
	 * firmware of other ESP chips 
	 * author: Boris du Reau date: 03/10/2022 
	 * email: boris.dureau@neuf.fr
	 * github: https://github.com/bdureau
	 * My main routine is just to test the ESP32 firmware flash. You will need to modify it
	 * to flash your firmware The objective of the port is to give people a good
	 * start. If you leave it as it is it will detect your chip and flash the blink program to your board
	 * 
	 * This have been successfully tested with the following chips ESP32, ESP32S3, ESP32C3 and ESP8266
	 * Some of the boards that I have tested are : LILY GO, WROOM modules
	 * If you need some other chips or ESP32 board let me know and I will try to add them
	 */
	public static void main(String[] args) {
		boolean syncSuccess = false;
		// get the first port available, you might want to change that
		comPort = SerialPort.getCommPorts()[0];

		String portName = comPort.getDescriptivePortName();
		if(DEBUG)
			System.out.println("connected to: " + portName);
		
		// initalize at 115200 bauds
		comPort.setBaudRate(ESP_ROM_BAUD);
		comPort.openPort();

		comPort.flushIOBuffers();

		// let's put the ship in boot mode
		enterBootLoader();
		if(DEBUG)
			System.out.println("Done with bootloader");

		comPort.flushIOBuffers();

		// first do the sync
		for (int i = 0; i < 3; i++) {
			comPort.flushIOBuffers();
		
			System.out.println("sync" + i);
			if (sync() == 0) {

			} else {
				syncSuccess = true;
				System.out.println("Sync Success!!!");
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {

				}
				break;
			}
		}

		if (syncSuccess) {
			// let's detect the chip
			// should work with an ESP32, ESP32S3, ESP32C3, ESP8266 because this is what the program
			// is for
			chip = detectChip();
			if (chip == ESP32)
				System.out.println("chip is ESP32");
			if (chip == ESP32S3)
				System.out.println("chip is ESP32S3");
			if (chip == ESP32C3)
				System.out.println("chip is ESP32C3");
			if (chip == ESP8266)
				System.out.println("chip is ESP8266");

			System.out.println(chip);

			// now that we have initialised the chip we can change the baud rate to 921600
			// first we tell the chip the new baud rate
			// not that we do not do it for ESP8266
			if (chip != ESP8266) {
				System.out.println("Changing baudrate to 921600");
				byte pkt[] = _appendArray(_int_to_bytearray(921600), _int_to_bytearray(0));
				sendCommand((byte) ESP_CHANGE_BAUDRATE, pkt, 0, 100);

				// second we change the com port baud rate
				comPort.setBaudRate(921600);
				// let's wait
				try {
					Thread.sleep(50);
				} catch (InterruptedException e) {
					
				}
			}
			// flush anything on the port
			comPort.flushIOBuffers();

			init();

			// Those are the files you want to flush
			// I am providing tests with the blink program 
			// They might not work for you depending of your LED wiring
			// you will need to change the test firmware file path 
			// the test firmwares are in the resources directories
			if (chip == ESP32) {
				byte file1[] = readFile("e:\\data\\ESP32\\boot_app0.bin");
				flashCompressedData(file1, 0xe000, 0); 
				 byte file2[] = readFile("e:\\data\\ESP32\\ESP32Blink.ino.bootloader.bin");
				 flashCompressedData(file2, 0x1000, 0); 
				 byte file3[] = readFile("e:\\data\\ESP32\\ESP32Blink.ino.bin");
				 flashCompressedData(file3, 0x10000, 0);
				 byte file4[] = readFile("e:\\data\\ESP32\\ESP32Blink.ino.partitions.bin"); 
				 flashCompressedData(file4, 0x8000, 0);
			}
			
			if (chip == ESP32C3) {
				byte file1[] = readFile("e:\\data\\ESP32\\ESP32C3\\boot_app0.bin");
				flashCompressedData(file1, 0xe000, 0);
				byte file2[] = readFile("e:\\data\\ESP32\\ESP32C3\\ESP32C3Blink.ino.bootloader.bin");
				flashCompressedData(file2, 0x0000, 0);
				byte file3[] = readFile("e:\\data\\ESP32\\ESP32C3\\ESP32C3Blink.ino.bin");
				flashCompressedData(file3, 0x10000, 0);
				byte file4[] = readFile("e:\\data\\ESP32\\ESP32C3\\ESP32C3Blink.ino.partitions.bin");
				flashCompressedData(file4, 0x8000, 0);
			}
			if (chip == ESP32S3) {
				byte file1[] = readFile("e:\\data\\ESP32\\ESP32S3\\boot_app0.bin");
				flashCompressedData(file1, 0xe000, 0);
				byte file2[] = readFile("e:\\data\\ESP32\\ESP32S3\\ESP32S3Blink.ino.bootloader.bin");
				flashCompressedData(file2, 0x0000, 0);
				byte file3[] = readFile("e:\\data\\ESP32\\ESP32S3\\ESP32S3Blink.ino.bin");
				flashCompressedData(file3, 0x10000, 0);
				byte file4[] = readFile("e:\\data\\ESP32\\ESP32s3\\ESP32S3Blink.ino.partitions.bin");
				flashCompressedData(file4, 0x8000, 0);
			}
			
			if (chip == ESP8266) {
				//Flash uncompress file
				//byte file1[] = readFile2("/ESP8266/ESP8266_Blink.ino.bin");
				byte file1[] = readFile("E:\\data\\ESP32\\ESP8266\\ESP8266Blink.ino.bin");
				flashData(file1, 0x0000, 0);
			}
			

			
			flash_finish(); // not sure it is usefull
			// we have finish flashing lets reset the board so that the program can start
			reset();

			System.out.println("done ");
		}
		// closing serial port
		comPort.closePort();
	}

	/*
	 * This will initialise the chip
	 */
	public static int sync() {
		int x;
		int response = 0;
		byte cmddata[] = new byte[36];

		cmddata[0] = (byte) (0x07);
		cmddata[1] = (byte) (0x07);
		cmddata[2] = (byte) (0x12);
		cmddata[3] = (byte) (0x20);
		for (x = 4; x < 36; x++) {
			cmddata[x] = (byte) (0x55);
		}

		for (x = 0; x < 7; x++) {

			byte cmd = (byte) ESP_SYNC;
			cmdRet ret = sendCommand(cmd, cmddata, 0, 100);
			if (ret.retCode == 1) {
				response = 1;
				break;
			}
		}
		return response;
	}

	/*
	 * This will send a command to the chip
	 */
	public static cmdRet sendCommand(byte op, byte buffer[], int chk, int timeout) {

		comPort.flushIOBuffers();
		cmdRet retVal = new cmdRet();
		int i = 0;
		byte data[] = new byte[8 + buffer.length];
		data[0] = 0x00;
		data[1] = op;
		data[2] = (byte) ((buffer.length) & 0xFF);
		data[3] = (byte) ((buffer.length >> 8) & 0xFF);
		data[4] = (byte) ((chk & 0xFF));
		data[5] = (byte) ((chk >> 8) & 0xFF);
		data[6] = (byte) ((chk >> 16) & 0xFF);
		data[7] = (byte) ((chk >> 24) & 0xFF);

		for (i = 0; i < buffer.length; i++) {
			data[8 + i] = buffer[i];
		}

		int ret = 0;
		retVal.retCode = -1;
		byte buf[] = slipEncode(data);

		ret = comPort.writeBytes(buf, buf.length);
		if(DEBUG)
			System.out.println(printHex(buffer));

		try {
			Thread.sleep(timeout);
		} catch (InterruptedException e) {
		}

		for (int j = 0; j < 100; j++) {
			int numRead = comPort.readBytes(retVal.retValue, retVal.retValue.length);

			if (numRead == 0) {
				retVal.retCode = -1;
			} else if (numRead == -1) {
				retVal.retCode = -1;
			}

			if (retVal.retValue[0] != (byte) 0xC0) {
				retVal.retCode = -1;
			}

			if (retVal.retValue[0] == (byte) 0xC0) {
				retVal.retCode = 1;
				return retVal;
			}
		}
		return retVal;
	}

	/*
	 * This will do a SLIP encode
	 */
	public static byte[] slipEncode(byte buffer[]) {
		
		byte encoded[] = new byte[] { (byte) (0xC0) };

		for (int x = 0; x < buffer.length; x++) {
			if (buffer[x] == (byte) (0xC0)) {
				encoded = _appendArray(encoded, new byte[] { (byte) (0xDB) });
				encoded = _appendArray(encoded, new byte[] { (byte) (0xDC) });

			} else if (buffer[x] == (byte) (0xDB)) {
				encoded = _appendArray(encoded, new byte[] { (byte) (0xDB) });
				encoded = _appendArray(encoded, new byte[] { (byte) (0xDD) });
			} else {
				encoded = _appendArray(encoded, new byte[] { buffer[x] });
			}
		}
		encoded = _appendArray(encoded, new byte[] { (byte) (0xC0) });

		return encoded;
	}

	/*
	 * This does a reset in order to run the prog after flash
	 */
	public static void reset() {

		comPort.setRTS();
		try {
			Thread.sleep(100);
		} catch (InterruptedException e) {
		}
		comPort.clearRTS();
	}

	/*
	 * enter bootloader mode
	 */
	public static void enterBootLoader() {
		// reset bootloader
		comPort.clearDTR();
		comPort.setRTS();
		try {
			Thread.sleep(100);
		} catch (InterruptedException e) {
		}

		comPort.setDTR();
		comPort.clearRTS();
		try {
			Thread.sleep(500);
		} catch (InterruptedException e) {
		}

		comPort.clearDTR();
	}

	/*
	 * @name flash_defl_block Send one compressed block of data to program into SPI
	 * Flash memory
	 */
	public static cmdRet flash_defl_block(byte data[], int seq, int timeout) {
		cmdRet retVal;

		byte pkt[] = _appendArray(_int_to_bytearray(data.length), _int_to_bytearray(seq));
		pkt = _appendArray(pkt, _int_to_bytearray(0));
		pkt = _appendArray(pkt, _int_to_bytearray(0)); // not sure
		pkt = _appendArray(pkt, data);

		retVal = sendCommand((byte) ESP_FLASH_DEFL_DATA, pkt, _checksum(data), timeout);
		return retVal;
	}

	public static cmdRet flash_block(byte data[], int seq, int timeout) {
		cmdRet retVal;

		byte pkt[] = _appendArray(_int_to_bytearray(data.length), _int_to_bytearray(seq));
		pkt = _appendArray(pkt, _int_to_bytearray(0));
		pkt = _appendArray(pkt, _int_to_bytearray(0)); // not sure
		pkt = _appendArray(pkt, data);

		retVal = sendCommand((byte) ESP_FLASH_DATA, pkt, _checksum(data), timeout);
		return retVal;
	}

	/*
	 * 
	 */
	public static void init() {

		int _flashsize = 4 * 1024 * 1024;

		if (!IS_STUB) {
			try {
				System.out.println("No stub...");
				// byte pkt[] = _int_to_bytearray(0); // 4 or 8 zero's? not sure
				byte pkt[] = _appendArray(_int_to_bytearray(0), _int_to_bytearray(0));

				System.out.println("Enabling default SPI flash mode...");
				sendCommand((byte) ESP_SPI_ATTACH, pkt, 0, 100);
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}

		// We are hardcoding 4MB flash for an ESP32
		System.out.println("Configuring flash size...");

		byte pkt2[] = _appendArray(_int_to_bytearray(0), _int_to_bytearray(_flashsize));
		pkt2 = _appendArray(pkt2, _int_to_bytearray(/* 0x10000 */ 64 * 1024));
		pkt2 = _appendArray(pkt2, _int_to_bytearray(/* 4096 */ 4 * 1024));
		pkt2 = _appendArray(pkt2, _int_to_bytearray(256));
		pkt2 = _appendArray(pkt2, _int_to_bytearray(0xFFFF));

		sendCommand((byte) ESP_SPI_SET_PARAMS, pkt2, 0, 100);

	}

	/*
	 * @name flashData Program a full, uncompressed binary file into SPI Flash at a
	 * given offset. If an ESP32 and md5 string is passed in, will also verify
	 * memory. ESP8266 does not have checksum memory verification in ROM
	 */
	public static void flashCompressedData(byte binaryData[], int offset, int part) {
		int filesize = binaryData.length;
		System.out.println("\nWriting data with filesize: " + filesize);

		byte image[] = compressBytes(binaryData);

		int blocks = flash_defl_begin(filesize, image.length, offset);

		int seq = 0;
		int written = 0;
		int position = 0;

		long t1 = System.currentTimeMillis();

		while (image.length - position > 0) {

			double percentage = Math.floor(100 * (seq + 1) / blocks);
			System.out.println("percentage: " + percentage);

			byte block[];

			if (image.length - position >= FLASH_WRITE_SIZE) {
				block = _subArray(image, position, FLASH_WRITE_SIZE);
			} else {
				// Pad the last block
				block = _subArray(image, position, image.length - position);
			}

			int ERASE_WRITE_TIMEOUT_PER_MB = 40;
			int block_timeout = timeout_per_mb(ERASE_WRITE_TIMEOUT_PER_MB, FLASH_WRITE_SIZE);
		
			cmdRet retVal;
			// not using the block timeout yet need to modify the senCommand to have a
			// proper timeout
			retVal = flash_defl_block(block, seq, /* block_timeout */ 100);
			if (retVal.retCode == -1) {
				System.out.println("Retry because Ret code:" + retVal.retCode);
				System.out.println(printHex(retVal.retValue));
				retVal = flash_defl_block(block, seq, /* block_timeout */ 100);
			}
			seq += 1;
			written += block.length;
			position += FLASH_WRITE_SIZE;
			System.out.println("Ret code:" + retVal.retCode);
			//System.out.println("Ret code:" + retVal.retValue.toString());
			if(DEBUG)
				System.out.println(printHex(retVal.retValue));
		}

		long t2 = System.currentTimeMillis();
		System.out.println("Took " + (t2 - t1) + "ms to write " + filesize + " bytes");
	}

	public static void flashData(byte binaryData[], int offset, int part) {
		int filesize = binaryData.length;
		System.out.println("\nWriting data with filesize: " + filesize);

		byte image[] = binaryData; //compressBytes(binaryData);

		int blocks = flash_begin(filesize,  offset);

		int seq = 0;
		int written = 0;
		int position = 0;

		long t1 = System.currentTimeMillis();

		while (image.length - position > 0) {

			double percentage = Math.floor(100 * (seq + 1) / blocks);
			System.out.println("percentage: " + percentage);

			byte block[];

			if (image.length - position >= FLASH_WRITE_SIZE) {
				block = _subArray(image, position, FLASH_WRITE_SIZE);
			} else {
				// Pad the last block
				block = _subArray(image, position, image.length - position);
			}

			int ERASE_WRITE_TIMEOUT_PER_MB = 40;
			int block_timeout = timeout_per_mb(ERASE_WRITE_TIMEOUT_PER_MB, FLASH_WRITE_SIZE);
		
			cmdRet retVal;
			// not using the block timeout yet need to modify the senCommand to have a
			// proper timeout
			retVal = flash_block(block, seq, /* block_timeout */ 100);
			if (retVal.retCode == -1) {
				System.out.println("Retry because Ret code:" + retVal.retCode);
				System.out.println(printHex(retVal.retValue));
				retVal = flash_block(block, seq, /* block_timeout */ 100);
			}
			seq += 1;
			written += block.length;
			position += FLASH_WRITE_SIZE;
			System.out.println("Ret code:" + retVal.retCode);
			//System.out.println("Ret code:" + retVal.retValue.toString());
			if(DEBUG)
				System.out.println(printHex(retVal.retValue));
		}

		long t2 = System.currentTimeMillis();
		System.out.println("Took " + (t2 - t1) + "ms to write " + filesize + " bytes");
	}

	
	private static int flash_defl_begin(int size, int compsize, int offset) {

		int num_blocks = (int) Math.floor((double) (compsize + FLASH_WRITE_SIZE - 1) / (double) FLASH_WRITE_SIZE);
		int erase_blocks = (int) Math.floor((double) (size + FLASH_WRITE_SIZE - 1) / (double) FLASH_WRITE_SIZE);
		// Start time
		long t1 = System.currentTimeMillis();

		int write_size, timeout;
		if (IS_STUB) {
			// using a stub (will use it in the future)
			write_size = size;
			timeout = 3000;
		} else {
			// no stub
			write_size = erase_blocks * FLASH_WRITE_SIZE;
			timeout = timeout_per_mb(ERASE_REGION_TIMEOUT_PER_MB, write_size);
		}

		System.out.println("Compressed " + size + " bytes to " + compsize + "...");

		byte pkt[] = _appendArray(_int_to_bytearray(write_size), _int_to_bytearray(num_blocks));
		pkt = _appendArray(pkt, _int_to_bytearray(FLASH_WRITE_SIZE));
		pkt = _appendArray(pkt, _int_to_bytearray(offset));
		if (chip == ESP32S3 || chip == ESP32C3 /*|| chip == ESP8266*/)
			pkt = _appendArray(pkt, _int_to_bytearray(0)); // ESP32S3 or ESP32C3

		try {
			sendCommand((byte) ESP_FLASH_DEFL_BEGIN, pkt, 0, timeout);

		} catch (Exception e) {
			e.printStackTrace();
		}

		// end time
		long t2 = System.currentTimeMillis();
		if (size != 0 && IS_STUB == false) {
			System.out.println("Took " + ((t2 - t1) / 1000) + "." + ((t2 - t1) % 1000) + "s to erase flash block");
		}
		return num_blocks;
	}

	private static int flash_begin(int size, int offset) {

		int num_blocks = (int) Math.floor((double) (size + FLASH_WRITE_SIZE - 1) / (double) FLASH_WRITE_SIZE);
		int erase_blocks = (int) Math.floor((double) (size + FLASH_WRITE_SIZE - 1) / (double) FLASH_WRITE_SIZE);
		// Start time
		long t1 = System.currentTimeMillis();

		int write_size, timeout;
		if (IS_STUB) {
			// using a stub (will use it in the future)
			write_size = size;
			timeout = 3000;
		} else {
			// no stub
			write_size = erase_blocks * FLASH_WRITE_SIZE;
			timeout = timeout_per_mb(ERASE_REGION_TIMEOUT_PER_MB, write_size);
		}

		//System.out.println("Compressed " + size + " bytes to " + compsize + "...");

		byte pkt[] = _appendArray(_int_to_bytearray(write_size), _int_to_bytearray(num_blocks));
		pkt = _appendArray(pkt, _int_to_bytearray(FLASH_WRITE_SIZE));
		pkt = _appendArray(pkt, _int_to_bytearray(offset));
		if (chip == ESP32S3 || chip == ESP32C3 /*|| chip == ESP8266*/)
			pkt = _appendArray(pkt, _int_to_bytearray(0)); // ESP32S3 or ESP32C3

		try {
			sendCommand((byte) ESP_FLASH_BEGIN, pkt, 0, timeout);

		} catch (Exception e) {
			e.printStackTrace();
		}

		// end time
		long t2 = System.currentTimeMillis();
		if (size != 0 && IS_STUB == false) {
			System.out.println("Took " + ((t2 - t1) / 1000) + "." + ((t2 - t1) % 1000) + "s to erase flash block");
		}
		return num_blocks;
	}

	
	/*
	 * Send a command to the chip to find out what type it is
	 */
	public static int detectChip() {
		int chipMagicValue = readRegister(CHIP_DETECT_MAGIC_REG_ADDR);

		int ret = 0;
		if (chipMagicValue == 0xfff0c101)
			ret = ESP8266;
		if (chipMagicValue == 0x00f01d83)
			ret = ESP32;
		if (chipMagicValue == 0x000007c6)
			ret = ESP32S2;
		if (chipMagicValue == 0x9)
			ret = ESP32S3;
		if (chipMagicValue == 0x6921506f || chipMagicValue == 0x1b31506f)
			ret = ESP32C3;
		if(DEBUG)
			System.out.println("chipMagicValue" + chipMagicValue);
		return ret;
	}
	////////////////////////////////////////////////
	// Some utility functions
	////////////////////////////////////////////////

	/*
	 * Just usefull for debuging to check what I am sending or receiving
	 */
	public static String printHex(byte[] bytes) {

		StringBuilder sb = new StringBuilder();
		sb.append("[ ");
		for (byte b : bytes) {
			sb.append(String.format("0x%02X ", b));
		}
		sb.append("]");
		return sb.toString();
	}

	/*
	 * This takes 2 arrays as params and return a concatenate array
	 */
	private static byte[] _appendArray(byte arr1[], byte arr2[]) {

		byte c[] = new byte[arr1.length + arr2.length];

		for (int i = 0; i < arr1.length; i++) {
			c[i] = arr1[i];
		}
		for (int j = 0; j < arr2.length; j++) {
			c[arr1.length + j] = arr2[j];
		}
		return c;
	}

	/*
	 * get part of an array
	 */
	private static byte[] _subArray(byte arr1[], int pos, int length) {

		byte c[] = new byte[length];

		for (int i = 0; i < (length); i++) {
			c[i] = arr1[i + pos];
		}
		return c;
	}

	/*
	 * Calculate the checksum. Still need to make sure that it works
	 */
	public static int _checksum(byte[] data) {
		int chk = ESP_CHECKSUM_MAGIC;
		int x = 0;
		for (x = 0; x < data.length; x++) {
			chk ^= data[x];
		}
		return chk;
	}

	public static int read_reg(int addr, int timeout) {
		cmdRet val;
		byte pkt[] = _int_to_bytearray(addr);
		val = sendCommand(ESP_READ_REG, pkt, 0, timeout);
		return val.retValue[0];
	}

	/**
	 * @name readRegister Read a register within the ESP chip RAM, returns a
	 *       4-element list
	 */
	public static int readRegister(int reg) {

		long retVals[] = { 0 };
		// int retVals = 0;
		cmdRet ret;

		Struct struct = new Struct();

		try {

			byte packet[] = _int_to_bytearray(reg);

			ret = sendCommand(ESP_READ_REG, packet, 0, 100);
			Struct myRet = new Struct();

			byte subArray[] = new byte[4];
			subArray[0] = ret.retValue[5];
			subArray[1] = ret.retValue[6];
			subArray[2] = ret.retValue[7];
			subArray[3] = ret.retValue[8];

			retVals = myRet.unpack("I", subArray);

		} catch (Exception e) {
			e.printStackTrace();
		}

		return (int) retVals[0];
	}

	/**
	 * @name getFlashWriteSize Get the Flash write size based on the chip
	 */
	/*
	 * public static int getFlashWriteSize() { return FLASH_WRITE_SIZE; }
	 */

	/**
	 * @name timeoutPerMb Scales timeouts which are size-specific
	 */
	private static int timeout_per_mb(int seconds_per_mb, int size_bytes) {
		int result = (int) (seconds_per_mb * ((double) size_bytes / (double) 1000000));
		if (result < 3000) {
			return 3000;
		} else {
			return result;
		}
	}

	private static byte[] _int_to_bytearray(int i) {
		byte ret[] = { (byte) (i & 0xff), (byte) ((i >> 8) & 0xff), (byte) ((i >> 16) & 0xff),
				(byte) ((i >> 24) & 0xff) };
		return ret;
	}

	private static byte[] short_to_bytearray(int i) {
		byte ret[] = { (byte) (i & 0xff), (byte) ((i >> 8) & 0xff) };
		return ret;
	}

	private static int _bytearray_to_int(byte i, byte j, byte k, byte l) {
		return ((int) i | (int) (j << 8) | (int) (k << 16) | (int) (l << 24));
	}

	/*
	 * Read a file and return an array of bytes
	 */
	public static byte[] readFile(String filename) {

		byte[] allBytes = null;
		try (InputStream inputStream = new FileInputStream(filename);) {
			long fileSize = new File(filename).length();
			System.out.println("fileSize from file open:" + fileSize);
			allBytes = new byte[(int) fileSize];
			int bytesRead = inputStream.read(allBytes);
		} catch (IOException ex) {
			ex.printStackTrace();
		}
		return allBytes;
	}
	
	/*
	 * readFile2(String filename)
	 * This is an attempt to get the examples from the resources directory
	 * Still in progress does not work yet
	 */
	public static byte[] readFile2(String filename) {

		byte[] allBytes = null;

        try (InputStream inputStream = testESPLoader.class.getResourceAsStream(filename);
             ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream()) {

            if (inputStream == null) {
                System.out.println("File not found: " + filename);
                return null; // Or throw an exception if you prefer
            }

            // Read the data from the InputStream into the ByteArrayOutputStream
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                byteArrayOutputStream.write(buffer, 0, bytesRead);
                System.out.println("Reading");
            }

            // Convert ByteArrayOutputStream to byte array
            allBytes = byteArrayOutputStream.toByteArray();

        } catch (IOException ex) {
            ex.printStackTrace();
        }

        return allBytes;
    }
	

	/**
	 * Compress a byte array using ZLIB compression
	 * 
	 * @param uncompressedData byte array of uncompressed data
	 * @return byte array of compressed data
	 */
	public static byte[] compressBytes(byte[] uncompressedData) {
		// Create the compressor with highest level of compression
		Deflater compressor = new Deflater();
		compressor.setLevel(Deflater.BEST_COMPRESSION);
		// compressor.setLevel(Deflater.SYNC_FLUSH);

		// Give the compressor the data to compress
		compressor.setInput(uncompressedData);
		compressor.finish();

		// Create an expandable byte array to hold the compressed data.
		// You cannot use an array that's the same size as the orginal because
		// there is no guarantee that the compressed data will be smaller than
		// the uncompressed data.
		ByteArrayOutputStream bos = new ByteArrayOutputStream(uncompressedData.length);

		// Compress the data
		byte[] buf = new byte[1024];
		while (!compressor.finished()) {
			int count = compressor.deflate(buf);
			bos.write(buf, 0, count);
		}
		try {
			bos.close();
		} catch (IOException e) {
		}

		// Get the compressed data
		byte[] compressedData = bos.toByteArray();
		return compressedData;
	}

	public static void flash_finish() {
		byte[] temp = new byte[2];
		temp[0] = (byte) (0x3C);
		temp[1] = (byte) (0x49);
		byte pkt[] = _appendArray(temp, _int_to_bytearray(1));
		sendCommand((byte) ESP_FLASH_END, pkt, 0, 100);
	}
}
