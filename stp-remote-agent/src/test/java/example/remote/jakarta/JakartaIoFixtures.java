// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example.remote.jakarta;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;

public final class JakartaIoFixtures {
	private JakartaIoFixtures() { }
	public static final class Reader implements ReadListener {
		@Override public void onDataAvailable() { }
		@Override public void onAllDataRead() { }
		@Override public void onError(Throwable failure) { }
	}
	public static final class Writer implements WriteListener {
		@Override public void onWritePossible() { }
		@Override public void onError(Throwable failure) { }
	}
	public static final class Input extends ServletInputStream {
		@Override public boolean isFinished() { return true; }
		@Override public boolean isReady() { return true; }
		@Override public void setReadListener(ReadListener listener) { }
		@Override public int read() { return -1; }
	}
	public static final class Output extends ServletOutputStream {
		@Override public boolean isReady() { return true; }
		@Override public void setWriteListener(WriteListener listener) { }
		@Override public void write(int value) { }
	}
}
