package me.osm.gazetter.dao;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;

public class FileWriteDao implements WriteDao {

	private static final Map<String, PrintWriter> writers = new HashMap<String, PrintWriter>();
	private File dir;
	
	public FileWriteDao(File dir) {
		this.dir = dir;
		dir.mkdirs();
	}
	
	@Override
	public void write(String line, String key) throws IOException {
		PrintWriter w = getWriter(key);
		synchronized (w) {
			w.println(line);
		}
	}

	private PrintWriter getWriter(String key) throws IOException {
		PrintWriter pw = writers.get(key);
		if(pw == null) {
			synchronized(writers) {
				pw = writers.get(key);
				if(pw == null) {
					File file = new File(dir.getAbsolutePath() + "/" + key);
					if(!file.exists()) {
						file.createNewFile();
					}
					
					FileOutputStream fos = new FileOutputStream(file, true);
					pw = new PrintWriter(fos);
					writers.put(key, pw);
				}
			}
		}
		return pw;
	}

	@Override
	public void close() {
		for(PrintWriter writer : writers.values()) {
			writer.flush();
			writer.close();
		}
	}

}
