/**
 *
 */
package org.waarp.ftp.client.transaction;

import java.io.File;
import java.util.Date;

import org.waarp.ftp.client.FtpClient;

/**
 * FTP Thread used to check multiple FTP clients in parallel with the test scenario
 * 
 * @author frederic
 * 
 */
public class FtpClientThread implements Runnable {
	private String id = null;

	private String server = null;

	private int port = 21;

	private String username = null;

	private String passwd = null;

	private String account = null;

	private String localFilename = null;

	private String remoteFilename = null;

	private int numberIteration = 1;

	private int type = 0;

	private int delay = 0;

	private int isSsl = 0;

	/**
	 * @param id
	 * @param server
	 * @param port
	 * @param username
	 * @param passwd
	 * @param account
	 * @param localFilename
	 * @param nb
	 * @param type
	 * @param delay
	 */
	public FtpClientThread(String id, String server, int port, String username,
			String passwd, String account, String localFilename, int nb,
			int type, int delay, int isSsl) {
		this.id = id;
		this.server = server;
		this.port = port;
		this.username = username;
		this.passwd = passwd;
		this.account = account;
		this.localFilename = localFilename;
		File local = new File(this.localFilename);
		this.remoteFilename = local.getName();
		this.numberIteration = nb;
		this.type = type;
		this.delay = (delay / 10) * 10;
		this.isSsl = isSsl;
	}

	public void run() {
		Ftp4JClientTransactionTest client = new Ftp4JClientTransactionTest(this.server,
				this.port, this.username, this.passwd, this.account, this.isSsl);
		// Thread.yield();
		// System.err.println(id+" connect");
		if (!client.connect()) {
			System.err.println(id + " Cant connect");
			FtpClient.numberKO.incrementAndGet();
			return;
		}
		try {
			if (this.numberIteration <= 0) {
				FtpClient.numberOK.incrementAndGet();
				return;// Only connect
			}
			// client.makeDir(this.id);
			// System.err.println(id+" change dir");
			client.changeDir(this.id);
			if (delay >= 10) {
				try {
					Thread.sleep(delay);
				} catch (InterruptedException e) {
				}
			} else {
				Thread.yield();
			}

			// System.err.println(id+" change type");
			client.changeFileType(true);
			if (type <= 0) {
				// System.err.println(id+" change mode passive");
				client.changeMode(true);
				if (type <= -10) {
					for (int i = 0; i < this.numberIteration; i++) {
						// System.err.println(id+" transfer store "+i);
						if (!client.transferFile(this.localFilename,
								this.remoteFilename, true)) {
							System.err.println((new Date()) +
									" Cant store file passive mode " + this.id);
							FtpClient.numberKO.incrementAndGet();
							return;
						} else {
							FtpClient.numberOK.incrementAndGet();
							if (delay > 0) {
								try {
									Thread.sleep(delay);
								} catch (InterruptedException e) {
								}
							}
						}
						// System.err.println(id+" end transfer store "+i);
					}
					Thread.yield();
				} else {
					for (int i = 0; i < this.numberIteration; i++) {
						// System.err.println(id+" transfer retr "+i);
						if (!client.transferFile(null,
								this.remoteFilename, false)) {
							System.err.println((new Date()) +
									" Cant retrieve file passive mode " + this.id);
							FtpClient.numberKO.incrementAndGet();
							return;
						} else {
							FtpClient.numberOK.incrementAndGet();
							if (delay > 0) {
								try {
									Thread.sleep(delay);
								} catch (InterruptedException e) {
								}
							}
						}
						// System.err.println(id+" end transfer retr "+i);
					}
					Thread.yield();
				}
			}
			if (type >= 0) {
				// System.err.println(id+" change mode active");
				client.changeMode(false);
				if (type >= 10) {
					for (int i = 0; i < this.numberIteration; i++) {
						// System.err.println(id+" transfer store "+i);
						if (!client.transferFile(this.localFilename,
								this.remoteFilename, true)) {
							System.err.println((new Date()) +
									" Cant store file active mode " + this.id);
							FtpClient.numberKO.incrementAndGet();
							return;
						} else {
							FtpClient.numberOK.incrementAndGet();
							if (delay > 0) {
								try {
									Thread.sleep(delay);
								} catch (InterruptedException e) {
								}
							}
						}
						// System.err.println(id+" transfer store end "+i);
					}
					Thread.yield();
				} else {
					for (int i = 0; i < this.numberIteration; i++) {
						// System.err.println(id+" transfer retr "+i);
						if (!client.transferFile(null,
								this.remoteFilename, false)) {
							System.err.println((new Date()) +
									" Cant retrieve file active mode " + this.id);
							FtpClient.numberKO.incrementAndGet();
							return;
						} else {
							FtpClient.numberOK.incrementAndGet();
							if (delay > 0) {
								try {
									Thread.sleep(delay);
								} catch (InterruptedException e) {
								}
							}
						}
						// System.err.println(id+" end transfer retr "+i);
					}
				}
			}
		} finally {
			// System.err.println(id+" disconnect");
			client.logout();
			// System.err.println(id+" end disconnect");
		}
	}

}
