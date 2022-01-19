/**
 * MIT License
 *
 * Copyright 2021 Matthias Gärtner
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to
 * deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or
 * sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */
package de.m9aertner.dbkr;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.text.PDFTextStripper;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

/**
 * Simple application of Apache PDFBox PDF decoder framework to decode Deutsche
 * Bank Kontoauszüge (bank statements) into machine-readable JSON documents.
 *
 * This is a command line application.
 *
 * One or more <code>*.PDF</code> files can be specificed on the command line.
 *
 * Recursion into folders is supported (-r). File names found during recursion
 * can optionally be checked against a prefix (e.g. "Kontoauszug_") so that
 * other PDFs in the source folder(s) are ignored.
 *
 * Output can be produced three ways: Normal operation decodes all bank
 * statements and their bank statement lines into an empty-line delimited stream
 * of JSON elements. Note that this is a true JSON file only for single input
 * PDFs. With multiple PDFs as input, it is a concatenation of JSON files,
 * suitable for post-processing, e.g. using "jq". Default output goes to stdout,
 * but an output file can be specified using the -o option.
 *
 * The second mode, a folder is created for each year/month[/day] for which a
 * booking line has been detected. This is enabled using -d with a base
 * directory. Day granularity is default, month granularity can be selected
 * using -m option. The "valuta" date is used as default for the relevant date,
 * using -b the "booking date" can be chosen as well. Target files that do exist
 * already will not be overwritten, unless -u (update) option is given.
 *
 * A third mode is to produce a *.json file next to the original PDF file (-j).
 * The PDF file name is kept, extension is changed, and all booking lines from
 * that PDF will end up in one JSON file.
 *
 * You can indicate end of options with double dash (--). File name arguments
 * starting with an octothorpe (#) and options starting with '-#' will be ignored
 * silently.
 */
public class DBKontoauszugReader {

	public static final String VERSION = "1.1.0";

	// default output stream, ignored with -d
	protected PrintStream out = null;

	// -q: Prevent number of booking lines processed from being output on out.
	protected boolean quiet = false;

	// -v: Verbose, emit generated file names, only for directory mode (-d)
	protected boolean verbose = false;

	// -p: default accept-all file filter, replaces with -p (prefix) option.
	protected FileFilter fileFilter = pathname -> true;

	// Maintain number of booking lines processed. One "Kontoauszug" will contain
	// zero, one or more such lines.
	protected int n = 0;

	// When using -o, we should close the output stream again.
	protected boolean closeStream = false;

	// -u: default do not overwrite, use with -d and -j
	protected boolean update = false;

	// -j: create *.json file next to original PDF file, mutually exclusive with -d
	// and -o
	protected boolean jsonMode = false;

	// -bl: create one JSON line per booking line (not with -d, -j)
	protected boolean lineMode = false;

	// -m: default day level = true, use with -d
	protected boolean month = false;

	// -b: default use valuta date for folder/file name generation, use with -d
	protected boolean buchungDate = false;

	// -1: default pretty printed JSON, -1 emity one-line JSON
	protected boolean oneLine = false;

	// -d: Folder-per-month or folder-per-day base directory, set via -d
	protected File baseDir;

	// default no recursion, applies to folder arguments
	protected boolean recurse = false;

	public static void main(String[] args) throws Exception {
		DBKontoauszugReader c = new DBKontoauszugReader();
		c.out = System.out;
		c.handleOptionsAndRun(args);
		if (!c.quiet) {
			c.out.println("Number of booking lines processed: " + c.n);
		}
	}

	public void handleOptionsAndRun(String[] args) throws Exception {
		boolean options = true;
		for (int i = 0; i < args.length; i++) {
			String arg = args[i];

			if (options && arg.equals("--")) {
				options = false;
			} else if (options && arg.startsWith("-")) {
				if (!arg.startsWith("-#")) {
					i = handleOption(arg, args, i);
				}
			} else {
				if (jsonMode && closeStream) {
					throw new IllegalArgumentException("Json mode is mutually exclusive with output file -o.");
				}
				if (jsonMode && baseDir != null) {
					throw new IllegalArgumentException("Json mode is mutually exclusive with directory mode -d.");
				}
				if (update && baseDir == null && !jsonMode) {
					throw new IllegalArgumentException(
							"Update flag -u can only be used with output directory -d or -j.");
				}
				if (month && baseDir == null) {
					throw new IllegalArgumentException("Month flag -m can only be used with output directory -d.");
				}
				if (jsonMode && lineMode) {
					throw new IllegalArgumentException("Json mode cannot output booking lines currently (-bl).");
				}
				options = false;
				handleArgument(arg);
			}
		}
		if (closeStream) {
			out.close();
		}
	}

	/**
	 * Handle one option
	 * 
	 * @param arg  Current option, e.g. '-r'
	 * @param args All options, to get the option's value, if any
	 * @param i    current option index
	 * @return updated option index, for options with value(s)
	 * @throws Exception
	 */
	protected int handleOption(String arg, String[] args, int i) throws Exception {
		if ("-r".equals(arg)) {
			recurse = true;
		} else if ("-p".equals(arg)) {
			fileFilter = new PrefixFileFilter(args[++i]);
		} else if ("-q".equals(arg)) {
			quiet = true;
		} else if ("-v".equals(arg)) {
			verbose = true;
		} else if ("-j".equals(arg)) {
			jsonMode = true;
		} else if ("-bl".equals(arg)) {
			lineMode = true;
		} else if ("-u".equals(arg)) {
			update = true;
		} else if ("-m".equals(arg)) {
			month = true;
		} else if ("-b".equals(arg)) {
			buchungDate = true; // default to ValutaDate
		} else if ("-1".equals(arg)) {
			oneLine = true; // default to pretty-print
		} else if ("-o".equals(arg)) {
			if (baseDir != null) {
				throw new IllegalArgumentException(
						"Output file -o and output directory -d options are mutually exclusive.");
			}
			if (closeStream) {
				out.close();
			}
			out = new PrintStream(new FileOutputStream(args[++i]));
			closeStream = true;
		} else if ("-d".equals(arg)) {
			if (closeStream) {
				throw new IllegalArgumentException(
						"Output file -o and output directory -d options are mutually exclusive.");
			}
			baseDir = new File(args[++i]);
			if (!baseDir.isDirectory()) {
				throw new IllegalArgumentException("Not a directory: " + baseDir);
			}
		} else if ("--version".equals(arg) || "-V".equals(arg)) {
			out.println("DBKontoauszugReader " + VERSION);
			i = 99999;
			quiet = true;
		}
		return i;
	}

	/**
	 * Handles the next argument (command line file name)
	 * 
	 * @param arg
	 * @throws Exception
	 */
	protected void handleArgument(String arg) throws Exception {
		if (!arg.startsWith("#")) { // hash marks a a comment (inactive) argument
			File f = new File(arg);
			if (f.exists()) {
				visit(f, true, false);
			} else {
				throw new FileNotFoundException(arg);
			}
		}
	}

	/**
	 * Visit one file. Process if it's a file, recurse if it's a folder.
	 * 
	 * @param f              May be a folder or file.
	 * @param recurse        if <code>true</code>, recurse into folder,
	 *                       <code>false</code> ignore folders.
	 * @param checkExtension <code>true</code> check PDF file extension,
	 *                       <code>false</code> do not check. Note that files given
	 *                       on the command line are not checked.
	 * @throws Exception
	 */
	protected void visit(File f, boolean recurse, boolean checkExtension) throws Exception {
		if (f.isFile()) {
			if (!checkExtension || f.getName().toLowerCase().endsWith(".pdf")) {
				visitFile(f);
			}
		} else if (recurse && f.isDirectory()) {
			// Recurse
			File[] listFiles = f.listFiles(fileFilter);
			for (int j = 0; j < listFiles.length; j++) {
				visit(listFiles[j], this.recurse, true);
			}
		}
	}

	/**
	 * Extract the text from the given file, then emit the booking, if any.
	 * 
	 * @param file
	 * @throws Exception
	 */
	protected void visitFile(File file) throws Exception {
		String text = convertPDFtoText(file);
		Booking b = decodeBooking(text, file);
		if (b != null) {
			emitBooking(b);
		}
	}

	/**
	 * Use PDFBox to extract all the text content from the file.
	 * <p>
	 * Note that we need to "fix" the broken ToUnicodeMap encoding of Deutsche Bank
	 * PDF files so that the decoder falls back to the simple WinAnsiEncoding, which
	 * works.
	 *
	 * @see https://stackoverflow.com/a/45922162/1037626
	 * @param file
	 * @return
	 * @throws Exception
	 */
	protected String convertPDFtoText(File file) throws Exception {
		String text = null;
		try (PDDocument document = PDDocument.load(file)) {

			for (int pageNr = 0; pageNr < document.getNumberOfPages(); pageNr++) {
				PDPage page = document.getPage(pageNr);
				PDResources resources = page.getResources();
				removeToUnicodeMaps(resources);
			}

			PDFTextStripper stripper = new PDFTextStripper();
			stripper.setSortByPosition(true);
			text = stripper.getText(document);
		}
		return text;
	}

	public static final Pattern P0 = Pattern.compile("^Kontoauszug vom " //
			+ "((\\d\\d)\\.(\\d\\d)\\.\\d{4}) bis " //
			+ "((\\d\\d)\\.(\\d\\d)\\.\\d{4})");

	public static final Pattern P1 = Pattern.compile("^00\\d{8,} / \\d{8,} / \\d{8,}");

	public static final Pattern P2 = Pattern.compile("^Buchung Valuta Vorgang Soll Haben");

	public static final Pattern P3 = Pattern.compile("^" //
			+ "((\\d\\d)\\.(\\d\\d)\\.) " //
			+ "((\\d\\d)\\.(\\d\\d)\\.) " //
			+ "(.+) " //
			+ "((-|\\+) \\d+(\\.\\d+)*,\\d\\d)");

	public static final Pattern P4 = Pattern.compile("^Filialnummer Kontonummer Neuer Saldo");

	/**
	 * Primitive state machine that decodes key lines from the PDF text line stream.
	 * Obviously, this is pretty <b>incomplete</b>, but it's <i>enough for my
	 * needs</i>.
	 * <p>
	 * Feel free to enhance this, e.g. by reading totals, account number, contact
	 * names et cetera.
	 * <p>
	 * I believe it is not possible to recover the <b>exact</b> contents of the
	 * booking line's text by just looking at the text extraction. Especially "line
	 * breaks" appear to take into account the non-monospace letter width of lines
	 * and if that overflows, a hard word-splitting break gets inserted.
	 * 
	 * @param text
	 * @param file
	 * @return
	 * @throws Exception
	 */
	protected Booking decodeBooking(String text, File file) throws Exception {
		int Z = 0;
		Booking b = null;
		BookingLine bl = null;
		BufferedReader br = new BufferedReader(new StringReader(text));
		while (Z >= 0) {
			String line = br.readLine();
			if (line == null) {
				break;
			}
			if (Z == 0) {
				Matcher m = matchLine(P0, line); // Statement header line (from, to)
				if (m != null) {
					b = new Booking();
					b.from = m.group(1);
					b.to = m.group(4);
					b.pdfFile = file;
					Z = 1;
					continue;
				}
			}
			if (Z == 1 || Z == 2 || Z == 3) {
				Matcher m = matchLine(P1, line); // End of page marker, i.e. vertical small print on bottom left
				if (m != null) {
					if (bl != null) {
						b.lines.add(bl);
						bl = null;
					}
					Z = 1;
					continue;
				}
			}
			if (Z == 1 || Z == 2 || Z == 3) {
				Matcher m = matchLine(P2, line); // Begin of bookings section (Buchung, Valuta, ..., Soll, Haben)
				if (m != null) {
					if (bl != null) {
						b.lines.add(bl);
						bl = null;
					}
					Z = 2;
					continue;
				}
			}
			if (Z == 2 || Z == 3) {
				Matcher m = matchLine(P3, line); // First booking line (Date Buchung, Date Valuta, ..., Amount)
				if (m != null) {
					if (bl != null) {
						b.lines.add(bl);
					}
					bl = new BookingLine();
					bl.setBuchung(m.group(1), b);
					bl.setValuta(m.group(4), b);
					bl.setText(m.group(7));
					bl.setAmountTxt(m.group(8));
					Z = 3;
					continue;
				}
			}
			if (Z == 3) {
				Matcher m = matchLine(P4, line); // Last page, after last booking line (Filialnummer, ..., Neuer Saldo) 
				if (m != null) {
					if (bl != null) {
						b.lines.add(bl);
					}
					bl = null;
					break;
				}
			}
			if (Z == 3 && !line.isEmpty()) { // Subsequent booking line text
				bl.addText(line);
				continue;
			}
		}
		return b;
	}

	/**
	 * Match the line against the supplied pattern.
	 * 
	 * @param p
	 * @param line
	 * @return If a match is present, return the Matcher so caller can access the
	 *         groups, <code>null</code> on no match.
	 */
	protected Matcher matchLine(Pattern p, String line) {
		Matcher matcher = p.matcher(line);
		return matcher.matches() ? matcher : null;
	}

	/**
	 * A booking has been assembled for a file. Emit it.
	 * <p>
	 * Depending on options, this will either be into individual JSON files, one per
	 * booking line, in directory mode (-d).
	 * <p>
	 * Or, we just output the JSON to the output stream.
	 * <p>
	 * JSON is pretty-printed, unless one-line mode is used (-1).
	 * 
	 * @param b
	 * @throws IOException
	 */
	protected void emitBooking(Booking b) throws IOException {
		ObjectWriter writer = null;
		if (oneLine) {
			writer = new ObjectMapper().writer();
		} else {
			writer = new ObjectMapper().writerWithDefaultPrettyPrinter();
		}
		if (jsonMode) {
			String fn = b.pdfFile.getName();
			int lastDot = fn.lastIndexOf('.');
			if (lastDot > 0) {
				fn = fn.substring(0, lastDot);
			}
			fn = fn + ".json";
			File bookingFile = new File(b.pdfFile.getParent(), fn);
			if (!bookingFile.exists() || update) {
				n += b.lines.size();
				writer.writeValue(bookingFile, b);
				if (verbose) {
					out.println(bookingFile.toString());
				}
			}
		} else if (baseDir != null) {
			int nFile = 1;
			String lastDate = "";
			for (int i = 0; i < b.lines.size(); i++) {
				BookingLine bl = b.lines.get(i);
				String dt = buchungDate ? bl.buchung : bl.valuta;
				if (!lastDate.equals(dt)) {
					lastDate = dt;
					nFile = 1;
				}
				String dd = dt.substring(0, 2);
				String mm = dt.substring(3, 5);
				String yyyy = dt.substring(6, 10);
				File dir = new File(new File(baseDir, yyyy), mm);
				if (!month) {
					dir = new File(dir, dd);
				}
				String fn = String.format("%s-%s-%s-%08d.json", yyyy, mm, dd, nFile++);
				File bookingLineFile = new File(dir, fn);
				if (!bookingLineFile.exists() || update) {
					dir.mkdirs();
					// Include pointer to where this came from into the booking line JSON
					bl.pdfName = b.pdfFile.getName();
					bl.pdfLine = String.valueOf(i);
					writer.writeValue(bookingLineFile, bl);
					n++;
					if (verbose) {
						out.println(bookingLineFile.toString());
					}
				}
			}
		} else if (lineMode) {
			n += b.lines.size();
			for (int i = 0; i < b.lines.size(); i++) {
				BookingLine bl = b.lines.get(i);
				bl.pdfName = b.pdfFile.getName();
				bl.pdfLine = String.valueOf(i);
				String json = writer.writeValueAsString(bl);
				out.println(json);
			}
		} else {
			n += b.lines.size();
			String json = writer.writeValueAsString(b);
			out.println(json);
		}
	}

	public static class PrefixFileFilter implements FileFilter {
		private String prefix;

		PrefixFileFilter(String prefix) {
			this.prefix = prefix;
		}

		@Override
		public boolean accept(File pathname) {
			return pathname.isDirectory() || pathname.getName().startsWith(prefix);
		}
	}

	void removeToUnicodeMaps(PDResources pdResources) throws IOException {
		COSDictionary resources = pdResources.getCOSObject();

		COSDictionary fonts = asDictionary(resources, COSName.FONT);
		if (fonts != null) {
			for (COSBase object : fonts.getValues()) {
				while (object instanceof COSObject)
					object = ((COSObject) object).getObject();
				if (object instanceof COSDictionary) {
					COSDictionary font = (COSDictionary) object;
					font.removeItem(COSName.TO_UNICODE);
				}
			}
		}

		for (COSName name : pdResources.getXObjectNames()) {
			PDXObject xobject = pdResources.getXObject(name);
			if (xobject instanceof PDFormXObject) {
				PDResources xobjectPdResources = ((PDFormXObject) xobject).getResources();
				removeToUnicodeMaps(xobjectPdResources);
			}
		}
	}

	COSDictionary asDictionary(COSDictionary dictionary, COSName name) {
		COSBase object = dictionary.getDictionaryObject(name);
		return object instanceof COSDictionary ? (COSDictionary) object : null;
	}

	@JsonInclude(Include.NON_NULL) //
	public static class BookingLine {
		public String pdfName;
		public String pdfLine;
		private String buchung;    // e.g. "02.01.2018"
		private String valuta;     // e.g. "29.12.2017"
		private String text;
		private String amountTxt;  // e.g. "- 600,00"
		private BigDecimal amount; // e.g. -60000

		public String getBuchung() {
			return buchung;
		}

		public void setBuchung(String buchung, Booking b) {
			this.buchung = toFullDate(buchung, b);
		}

		public String getValuta() {
			return valuta;
		}

		public void setValuta(String valuta, Booking b) {
			this.valuta = toFullDate(valuta, b);
		}

		public String getAmount() {
			return amountTxt;
		}

		public BigDecimal getAmountCt() {
			return amount;
		}

		public void setAmountTxt(String amountTxt) {
			this.amountTxt = amountTxt;
			this.amount = new BigDecimal(amountTxt.replaceAll("[ \\.\\,]", ""));
		}

		public String getText() {
			return text;
		}

		public void setText(String t) {
			text = t;
		}

		public void addText(String t) {
			text = text + " " + t;
		}

		protected String toFullDate(String dt4, Booking b) {
			// This will not be accurate for bank statements that span more than 11 months.
			// I do not have any like that, though.
			String dt_month = dt4.substring(3); // e.g. "02." for "22.02."
			String from_month = b.from.substring(3, 6); // e.g. "12." for "29.12.2021"
			String yr = (from_month.compareTo(dt_month) <= 0) ? b.from.substring(6) : b.to.substring(6);
			return dt4 + yr;
		}
	}

	public static class Booking {
		public File pdfFile; 
		public String from;  // e.g. "30.12.2017"
		public String to;    // e.g. "31.01.2018"
		public List<BookingLine> lines = new ArrayList<>();
	}
}

// The End
