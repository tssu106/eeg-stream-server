package eeg.edf;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class EdfReader implements AutoCloseable {

    private final RandomAccessFile file;
    private final FileChannel channel;
    private final EdfHeader header;

    public EdfReader(Path path) throws IOException {
        this.file = new RandomAccessFile(path.toFile(), "r");
        this.channel = file.getChannel();
        this.header = readHeader();
    }

    public EdfHeader header() {
        return header;
    }

    private EdfHeader readHeader() throws IOException {
        ByteBuffer fixed = readBytes(0, 256);

        int headerBytes = parseInt(fixed, 184, 8);
        int numRecords = parseInt(fixed, 236, 8);
        double recordDuration = parseDouble(fixed, 244, 8);
        int numSignals = parseInt(fixed, 252, 4);

        ByteBuffer signalArea = readBytes(256, numSignals * 256);

        String[] labels = readField(signalArea, numSignals, 16, 0);
        int afterLabels = numSignals * 16;
        String[] transducerTypes = readField(signalArea, numSignals, 80, afterLabels);
        int afterTransducer = afterLabels + numSignals * 80;
        String[] dimensions = readField(signalArea, numSignals, 8, afterTransducer);
        int afterDimensions = afterTransducer + numSignals * 8;
        double[] physMins = readDoubleField(signalArea, numSignals, 8, afterDimensions);
        int afterPhysMin = afterDimensions + numSignals * 8;
        double[] physMaxs = readDoubleField(signalArea, numSignals, 8, afterPhysMin);
        int afterPhysMax = afterPhysMin + numSignals * 8;
        int[] digMins = readIntField(signalArea, numSignals, 8, afterPhysMax);
        int afterDigMin = afterPhysMax + numSignals * 8;
        int[] digMaxs = readIntField(signalArea, numSignals, 8, afterDigMin);
        int afterDigMax = afterDigMin + numSignals * 8;
        int afterPrefiltering = afterDigMax + numSignals * 80;
        int[] samplesPerRecord = readIntField(signalArea, numSignals, 8, afterPrefiltering);

        List<EdfSignalHeader> signals = new ArrayList<>(numSignals);
        for (int i = 0; i < numSignals; i++) {
            double sfreq = samplesPerRecord[i] / recordDuration;
            signals.add(new EdfSignalHeader(
                    i,
                    labels[i],
                    dimensions[i],
                    physMins[i],
                    physMaxs[i],
                    digMins[i],
                    digMaxs[i],
                    samplesPerRecord[i],
                    sfreq
            ));
        }

        return new EdfHeader(numRecords, recordDuration, numSignals, headerBytes, signals);
    }

    public int[] readDigitalSamples(int channelIndex, double startSec, double durationSec) throws IOException {
        EdfSignalHeader signal = header.signal(channelIndex);
        int recordSizeSamples = header.signals().stream().mapToInt(EdfSignalHeader::samplesPerRecord).sum();

        int firstSample = (int) Math.round(startSec * signal.sfreq());
        int sampleCount = (int) Math.round(durationSec * signal.sfreq());

        int[] result = new int[sampleCount];
        int filled = 0;
        int recordIndex = firstSample / signal.samplesPerRecord();
        int sampleInRecord = firstSample % signal.samplesPerRecord();

        int offsetWithinRecord = 0;
        for (int i = 0; i < channelIndex; i++) {
            offsetWithinRecord += header.signal(i).samplesPerRecord();
        }

        while (filled < sampleCount) {
            long recordStart = header.headerBytes() + (long) recordIndex * recordSizeSamples * 2L;
            int samplesToReadFromRecord = Math.min(signal.samplesPerRecord() - sampleInRecord, sampleCount - filled);

            long byteOffset = recordStart + (long) (offsetWithinRecord + sampleInRecord) * 2L;
            ByteBuffer buf = readBytes(byteOffset, samplesToReadFromRecord * 2);
            buf.order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < samplesToReadFromRecord; i++) {
                result[filled++] = buf.getShort();
            }

            recordIndex++;
            sampleInRecord = 0;
        }

        return result;
    }

    public double[] readPhysicalSamples(int channelIndex, double startSec, double durationSec) throws IOException {
        EdfSignalHeader signal = header.signal(channelIndex);
        int[] digital = readDigitalSamples(channelIndex, startSec, durationSec);
        double[] physical = new double[digital.length];
        for (int i = 0; i < digital.length; i++) {
            physical[i] = signal.toPhysical(digital[i]);
        }
        return physical;
    }

    private ByteBuffer readBytes(long offset, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        channel.read(buffer, offset);
        buffer.flip();
        return buffer;
    }

    private static String parseAscii(ByteBuffer buf, int offset, int length) {
        byte[] bytes = new byte[length];
        buf.get(offset, bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.US_ASCII).trim();
    }

    private static int parseInt(ByteBuffer buf, int offset, int length) {
        return Integer.parseInt(parseAscii(buf, offset, length));
    }

    private static double parseDouble(ByteBuffer buf, int offset, int length) {
        return Double.parseDouble(parseAscii(buf, offset, length));
    }

    private static String[] readField(ByteBuffer buf, int count, int fieldLength, int startOffset) {
        String[] values = new String[count];
        for (int i = 0; i < count; i++) {
            values[i] = parseAscii(buf, startOffset + i * fieldLength, fieldLength);
        }
        return values;
    }

    private static int[] readIntField(ByteBuffer buf, int count, int fieldLength, int startOffset) {
        int[] values = new int[count];
        for (int i = 0; i < count; i++) {
            values[i] = parseInt(buf, startOffset + i * fieldLength, fieldLength);
        }
        return values;
    }

    private static double[] readDoubleField(ByteBuffer buf, int count, int fieldLength, int startOffset) {
        double[] values = new double[count];
        for (int i = 0; i < count; i++) {
            values[i] = parseDouble(buf, startOffset + i * fieldLength, fieldLength);
        }
        return values;
    }

    @Override
    public void close() throws IOException {
        file.close();
    }
}
