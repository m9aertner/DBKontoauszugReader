# DBKontoauszugReader
Decode Deutsche Bank Kontoauszug PDF files into JSON format.

# Why?

I have local copies of my bank statements from Deutsche Bank, **in PDF format**, including pretty old ones.

To do some stats and also to facilitate easier __searching__ through those statements, I whipped up a small program to convert the PDFs to JSON format.

Having them in textual JSON representation allows to post-process them using JSON tooling, e.g. "jq".
# Why not?

* You can download CSV representations of these statements from the DB online banking. Try that first. Not sure if you can access __old__ statements this way, though.
* Obviously, there are other solutions to make PDFs searchable. Apache Tika, Elasticsearch with plug-ins, Adobe Tools, or even your OS may give you search options already...

# Building Blocks

* Java JRE / JDK
* Apache PDFBox https://pdfbox.apache.org/
* A valuable hint on StackOverflow to make PDFBox decode these (broken) PDFs properly: https://stackoverflow.com/a/45922162/1037626
* Builds using Gradle, but since it's only one file and a handful of JARs, you could probably build this by hand as well.
* Note: I am using Java 11 currently. YMMV with other versions, I guess anything beyond Java 8 including should work.

# Synopsis

    java de.m9aertner.dbkr.DBKontoauszugReader <options> <one or more folders, PDF files>

      -r             Recursive mode. Given a folder on the command line, recurse into subfolders.
                     Default is to look into the given folder, but to not recurse deeper.
      -p             Prefix. Consider only files whose name have this prefix, e.g. -p Kontoauszug_
      -q             Quiet. Do not report the number of booking lines written at the end of the run.
      -v             Verbose mode. Show which files have been (re-)created. Only with -d and -j modes.
      -j             JSON mode. Put a *.json file next to any input *.pdf file, with all the bookings
                     from that PDF in one JSON.
      -1             JSON single-line mode. Default is to pretty-print the generated JSON.
      -bl            JSON booking-line mode. Implicit with -d. Does not work with -j currently.
      -o             Output file mode. Send all JSONs to this output file. Default is stdout.
      -d             Directory mode. Create a YYYY/MM/DD folder and put each booking line into its own
                     numbered JSON file there.
      -m             Month only. Do not create DD (day) folders in directory mode.
      -b             For directory mode, the JSON file name is derived from the booking line valuta
                     date (YYYY-MM-DD-nnnnnnnn.json)
                     With this setting active, the booking date is used instead.
      -u             Update/overwrite target file. Default is to not touch pre-existing target files.
      --version, -V  Show DBKontoauszugReader version, then quit.
      --             Mark end of options processing, all remaining command line items will be treated
                     as input.
      

# Building / Running

Build using gradlew. Or use Eclipse. Or use IDEA. Or just "javac".

I've just set up a small shell script / batch file that sets up the JARs, then does the conversions for __my__ (private) folder structures as required.

If you're more into Gradle, use the "distZip" mechanism to create a "distribution". For small ad-hoc stuff like this, I did not bother.

    SET DBKR_DIR=C:\...\DBKontoauszugReader
    SET GRADLE_CACHE_DIR=C:\Users\...\.gradle\caches\modules-2\files-2.1
    
    SET CLASSPATH=%DBKR_DIR%\app\bin\main
    SET CLASSPATH=%CLASSPATH%;%GRADLE_CACHE_DIR%\commons-logging\commons-logging\1.2\4bfc12adfe4842bf07b657f0369c4cb522955686\commons-logging-1.2.jar
    SET CLASSPATH=%CLASSPATH%;%GRADLE_CACHE_DIR%\org.apache.pdfbox\fontbox\2.0.23\1a6b960dd2c1b1f8a5f5d6668b2930b50ff4324d\fontbox-2.0.23.jar
    SET CLASSPATH=%CLASSPATH%;%GRADLE_CACHE_DIR%\org.hamcrest\hamcrest-core\1.3\42a25dc3219429f0e5d060061f71acb49bf010a0\hamcrest-core-1.3.jar
    SET CLASSPATH=%CLASSPATH%;%GRADLE_CACHE_DIR%\com.fasterxml.jackson.core\jackson-annotations\2.12.2\a770cc4c0a1fb0bfd8a150a6a0004e42bc99fca\jackson-annotations-2.12.2.jar
    SET CLASSPATH=%CLASSPATH%;%GRADLE_CACHE_DIR%\com.fasterxml.jackson.core\jackson-core\2.12.2\8df50138521d05561a308ec2799cc8dda20c06df\jackson-core-2.12.2.jar
    SET CLASSPATH=%CLASSPATH%;%GRADLE_CACHE_DIR%\com.fasterxml.jackson.core\jackson-databind\2.12.2\5f9d79e09ebf5d54a46e9f4543924cf7ae7654e0\jackson-databind-2.12.2.jar
    SET CLASSPATH=%CLASSPATH%;%GRADLE_CACHE_DIR%\junit\junit\4.13.1\cdd00374f1fee76b11e2a9d127405aa3f6be5b6a\junit-4.13.1.jar
    SET CLASSPATH=%CLASSPATH%;%GRADLE_CACHE_DIR%\org.apache.pdfbox\pdfbox\2.0.23\b89643d162c4e30b4fe39cfa265546cc506d4d18\pdfbox-2.0.23.jar

# Input

The PDF format that I have here seems to have been pretty stable over the last decade at least.

It's the Deutsche Bank PDFs with
* Customer address,
* Filiale address, (hard to decode as intermingled with the customer address on the same line in the intermediate text representation)
* "Kontoauszug",
* "Kontoinhaber", 
* Auszug#, Seite#, IBAN, BIC, "Alter Saldo",
* "Buchung Valuta Vorgang Soll Haben", (this is actually a key marker line which triggers the decoding)
* Booking lines...
* ... delimited at the bottom of each page by the "vertical magic numbers" on the bottom left of the page,
* or by the trailing "Filialnummer / Kontonummer" line.

# Sample Output

    Command:
    java de.m9aertner.dbkr.DBKontoauszugReader -v -p Kontoauszug -m -d C:\Bank\DeutscheBankJson -- C:\Bank\DeutscheBank

    Output in C:\Bank\DeutscheBankJson\2014\03\2014-03-04-00000001.json, one file per booking line, e.g.:
    {
      "pdfName" : "Kontoauszug_950.........EUR_2014-03-31_KK_950.........KD401V050401010.........pdf",
      "pdfLine" : "12",
      "buchung" : "04.03.2014",
      "valuta" : "04.03.2014",
      "text" : "SEPA Dauerauftrag an ............... IBAN DE..................67 BIC ........XXX Verwendungszweck/ Kundenreferenz ................ RINP Dauerauftrag",
      "amount" : "- 75,00",
      "amountCt" : -7500
    }

# Caveats

* The text extraction logic expects German language "Kontoauszug" PDFs only. If you live elsewhere and have an account with Deutsche Bank, the statements may be localized, which _will_ break the super-simple decoder here.
* The booking line texts are collapsed onto a single line, with a single space character inserted between the fragments ('lines'). Unfortunately, the Deutsche Bank renderer introduces hard word breaks with no discernible identification. Hence, the inserted space can break 'words' into pieces, inadvertently. In other words, expect some spurious spaces in the "text" section.
* Not all data is extracted from the PDF: since I apply this only to my single bank account, no IBAN detection is in place and no names are decoded. Also the saldi are not decoded.
* I wonder why Deutsche Bank does not embed machine-readable original content right into the PDFs, as embedded PDF content (XML, BER, EDI, whatever.). Such content might actually be  _present_, but I have not looked for it (yet). Properly dissecting these PDF files on even lower level, anyone?
* Deutsche Bank offers a "Demokonto" where you can get an impression about how their online banking _looks and feels_. I think this is actually a pretty nice idea. All static, though.Unfortunately, the button to download or view a bank statement PDF is not operational. Instead of a PDF document, a dialog box pops up indicating that this function is not available for the demonstration account. Why not just deliver a sample PDF with mock data, albeit formatted properly?

# Enjoy!
