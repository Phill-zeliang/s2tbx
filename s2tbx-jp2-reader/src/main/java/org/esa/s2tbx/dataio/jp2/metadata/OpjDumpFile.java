/*
 * Copyright (C) 2014-2015 CS-SI (foss-contact@thor.si.c-s.fr)
 * Copyright (C) 2014-2015 CS-Romania (office@c-s.ro)
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 */

package org.esa.s2tbx.dataio.jp2.metadata;

import org.esa.s2tbx.dataio.Utils;
import org.esa.s2tbx.dataio.openjpeg.CommandOutput;
import org.esa.s2tbx.dataio.openjpeg.OpenJpegExecRetriever;
import org.esa.s2tbx.dataio.openjpeg.OpenJpegUtils;
import org.esa.s2tbx.lib.openjpeg.BufferedRandomAccessFile;
import org.esa.s2tbx.lib.openjpeg.CODMarkerSegment;
import org.esa.s2tbx.lib.openjpeg.ContiguousCodestreamBox;
import org.esa.s2tbx.lib.openjpeg.IMarkers;
import org.esa.s2tbx.lib.openjpeg.JP2FileReader;
import org.esa.s2tbx.lib.openjpeg.QCDMarkerSegment;
import org.esa.s2tbx.lib.openjpeg.SIZMarkerSegment;
import org.esa.snap.core.datamodel.MetadataElement;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by kraftek on 7/15/2015.
 */
public class OpjDumpFile {

    private ImageInfo imageInfo;
    private CodeStreamInfo codeStreamInfo;
    private Jp2XmlMetadata metadata;

    public OpjDumpFile() {
    }

    public ImageInfo getImageInfo() {
        return imageInfo;
    }

    public CodeStreamInfo getCodeStreamInfo() { return codeStreamInfo; }

    public Jp2XmlMetadata getMetadata() {
        return metadata;
    }

    public void readHeaderWithInputStream(Path jp2File, int bufferSize, boolean canSetFilePosition) throws IOException {
        JP2FileReader fileFormatReader = new JP2FileReader();
        fileFormatReader.readFileFormat(jp2File, bufferSize, canSetFilePosition);

        ContiguousCodestreamBox contiguousCodestreamBox = fileFormatReader.getHeaderDecoder();
        SIZMarkerSegment sizMarkerSegment = contiguousCodestreamBox.getSiz();
        CODMarkerSegment codMarkerSegment = contiguousCodestreamBox.getCod();
        QCDMarkerSegment qcdMarkerSegment = contiguousCodestreamBox.getQcd();

        this.imageInfo = new ImageInfo();
        this.imageInfo.setX0(sizMarkerSegment.getImageLeftX());
        this.imageInfo.setY0(sizMarkerSegment.getImageTopY());
        this.imageInfo.setWidth(sizMarkerSegment.getImageWidth());
        this.imageInfo.setHeight(sizMarkerSegment.getImageHeight());

        for (int i = 0; i < sizMarkerSegment.getNumComps(); i++) {
            int dx = sizMarkerSegment.getComponentDxAt(i);
            int dy = sizMarkerSegment.getComponentDyAt(i);
            int precision = sizMarkerSegment.getComponentOriginBitDepthAt(i);
            boolean signed = sizMarkerSegment.isComponentOriginSignedAt(i);
            this.imageInfo.addComponent(dx, dy, precision, signed);
        }

        this.codeStreamInfo = new CodeStreamInfo();

        this.codeStreamInfo.setTx0(sizMarkerSegment.getTileLeftX());
        this.codeStreamInfo.setTy0(sizMarkerSegment.getTileTopY());
        this.codeStreamInfo.setTileWidth(sizMarkerSegment.getNominalTileWidth());
        this.codeStreamInfo.setTileHeight(sizMarkerSegment.getNominalTileHeight());
        this.codeStreamInfo.setNumTilesX(sizMarkerSegment.computeNumTilesX());
        this.codeStreamInfo.setNumTilesY(sizMarkerSegment.computeNumTilesY());
        this.codeStreamInfo.setNumLayers(codMarkerSegment.getNumberOfLayers());
        this.codeStreamInfo.setMct(codMarkerSegment.getMultipleComponenTransform());

        for (int i = 0; i < sizMarkerSegment.getNumComps(); i++) {
            CodeStreamInfo.TileComponentInfo tcInfo = new CodeStreamInfo.TileComponentInfo();
            tcInfo.setNumResolutions(codMarkerSegment.getNumberOfLevels() + 1);
            for (int k = 0; k < codMarkerSegment.getBlockCount(); k++) {
                tcInfo.addPreccInt(codMarkerSegment.computeBlockWidthExponentOffset(k), codMarkerSegment.computeBlockHeightExponentOffset(k));
            }
            if (qcdMarkerSegment.getQuantizationType() == IMarkers.SQCX_NO_QUANTIZATION) {
                for (int r = 0; r < qcdMarkerSegment.getResolutionLevels(); r++) {
                    for (int s = 0; s < qcdMarkerSegment.getSubbandsAtResolutionLevel(r); s++) {
                        tcInfo.addStepSize(0, qcdMarkerSegment.computeNoQuantizationExponent(r, s));
                    }
                }
            } else {
                for (int r = 0; r < qcdMarkerSegment.getResolutionLevels(); r++) {
                    for (int s = 0; s < qcdMarkerSegment.getSubbandsAtResolutionLevel(r); s++) {
                        int exponent = qcdMarkerSegment.computeExponent(r, s);
                        int mantissa = (int) qcdMarkerSegment.computeMantissa(r, s, exponent);
                        tcInfo.addStepSize(mantissa, exponent);
                    }
                }
            }
            this.codeStreamInfo.addComponentTileInfo(tcInfo);
        }

        List<String> xmlMetadata = fileFormatReader.getXmlMetadata();
        if (xmlMetadata != null && xmlMetadata.size() > 0) {
            this.metadata = Jp2XmlMetadata.create(Jp2XmlMetadata.class, xmlMetadata.get(0));
            this.metadata.setName("XML Metadata");
            for (int i= 1; i< xmlMetadata.size(); i++) {
                MetadataElement element = Jp2XmlMetadata.create(Jp2XmlMetadata.class, xmlMetadata.get(i)).getRootElement();
                metadata.getRootElement().addElement(element);
            }
        }
    }

    public void readHeaderWithOpenJPEG(Path localJp2File) throws InterruptedException, IOException {
        String pathToImageFile = localJp2File.toString();
        if (org.apache.commons.lang.SystemUtils.IS_OS_WINDOWS) {
            pathToImageFile = Utils.GetIterativeShortPathNameW(pathToImageFile);
        }

        ProcessBuilder builder = new ProcessBuilder(OpenJpegExecRetriever.getOpjDump(), "-i", pathToImageFile);
        builder.redirectErrorStream(true);

        String newLineSeparator = "\n";
        CommandOutput exit = OpenJpegUtils.runProcess(builder, newLineSeparator);
        if (exit.getErrorCode() == 0) {
            List<String> lines = new ArrayList<String>();
            Collections.addAll(lines, exit.getTextOutput().split(newLineSeparator));
            Iterator<String> iterator = lines.iterator();
            String currentLine;
            while (iterator.hasNext()) {
                currentLine = iterator.next();
                if (currentLine.startsWith("Image info")) {
                    extractImageInfo(iterator);
                } else if (currentLine.startsWith("Codestream info")) {
                    extractCodeStreamInfo(iterator);
                }
            }

            this.metadata = new Jp2XmlMetadataReader(localJp2File).read();
        } else {
            StringBuilder sbu = new StringBuilder();
            for (String fragment : builder.command()) {
                sbu.append(fragment);
                sbu.append(' ');
            }
            throw new IOException(String.format("Command [%s] failed with error code [%d], stdoutput [%s] and stderror [%s]", sbu.toString(), exit.getErrorCode(), exit.getTextOutput(), exit.getErrorOutput()));
        }
    }

    private void extractImageInfo(Iterator<String> iterator) {
        this.imageInfo = new ImageInfo();
        String currentLine;
        Map<String, String> values = new HashMap<>();
        while (iterator.hasNext()) {
            currentLine = iterator.next();
            if (currentLine.endsWith("{")) {
                extractImageInfoComponent(iterator);
            } else if (currentLine.endsWith("}")) {
                break;
            } else {
                String[] tokens = currentLine.replace("\t", "").split(", ");
                for (String token : tokens) {
                    if (token.contains("=")) {
                        values.put(token.substring(0, token.indexOf("=")).trim(), token.substring(token.indexOf("=") + 1));
                    }
                }
            }
        }
        for (String key : values.keySet()) {
            switch (key) {
                case "x0":
                    this.imageInfo.setX0(Integer.parseInt(values.get(key)));
                    break;
                case "y0":
                    this.imageInfo.setY0(Integer.parseInt(values.get(key)));
                    break;
                case "x1":
                    this.imageInfo.setWidth(Integer.parseInt(values.get(key)));
                    break;
                case "y1":
                    this.imageInfo.setHeight(Integer.parseInt(values.get(key)));
                    break;
            }
        }
    }

    private void extractImageInfoComponent(Iterator<String> iterator) {
        String currentLine;
        int dx = 0, dy = 0, prec = 8;
        boolean sgnd = false;
        Map<String, String> values = new HashMap<>();
        while (iterator.hasNext()) {
            currentLine = iterator.next();
            if (currentLine.endsWith("}")) {
                break;
            } else {
                String[] tokens = currentLine.replace("\t", "").split(", ");
                for (String token : tokens) {
                    if (token.contains("=")) {
                        values.put(token.substring(0, token.indexOf("=")).trim(), token.substring(token.indexOf("=") + 1));
                    }
                }
            }
        }
        for (String key : values.keySet()) {
            switch (key) {
                case "dx":
                    dx = Integer.parseInt(values.get(key));
                    break;
                case "dy":
                    dy = Integer.parseInt(values.get(key));
                    break;
                case "prec":
                    prec = Integer.parseInt(values.get(key));
                    break;
                case "sgnd":
                    sgnd = Integer.parseInt(values.get(key)) == 1;
                    break;
            }
        }
        this.imageInfo.addComponent(dx, dy, prec, sgnd);
    }

    private void extractCodeStreamInfo(Iterator<String> iterator) {
        String currentLine;
        this.codeStreamInfo = new CodeStreamInfo();
        Map<String, String> values = new HashMap<>();
        while (iterator.hasNext()) {
            currentLine = iterator.next();
            if (currentLine.contains("comp")) {
                extractTileComponent(iterator);
            } else if (currentLine.endsWith("}")) {
                break;
            } else {
                String[] tokens = currentLine.replace("\t", "").split(", ");
                for (String token : tokens) {
                    if (token.contains("=")) {
                        values.put(token.substring(0, token.indexOf("=")).trim(), token.substring(token.indexOf("=") + 1));
                    }
                }
            }
        }
        for (String key : values.keySet()) {
            switch (key) {
                case "tx0":
                    codeStreamInfo.setTx0(Integer.parseInt(values.get(key)));
                    break;
                case "ty0":
                    codeStreamInfo.setTy0(Integer.parseInt(values.get(key)));
                    break;
                case "tdx":
                    codeStreamInfo.setTileWidth(Integer.parseInt(values.get(key)));
                    break;
                case "tdy":
                    codeStreamInfo.setTileHeight(Integer.parseInt(values.get(key)));
                    break;
                case "tw":
                    codeStreamInfo.setNumTilesX(Integer.parseInt(values.get(key)));
                    break;
                case "th":
                    codeStreamInfo.setNumTilesY(Integer.parseInt(values.get(key)));
                    break;
                case "csty":
                    codeStreamInfo.setCsty(values.get(key));
                    break;
                case "prg":
                    codeStreamInfo.setPrg(values.get(key));
                    break;
                case "numlayers":
                    codeStreamInfo.setNumLayers(Integer.parseInt(values.get(key)));
                    break;
                case "mct":
                    codeStreamInfo.setMct(Integer.parseInt(values.get(key)));
                    break;
            }
        }
    }

    private void extractTileComponent(Iterator<String> iterator) {
        String currentLine;
        Map<String, String> values = new LinkedHashMap<>();
        CodeStreamInfo.TileComponentInfo tcInfo = new CodeStreamInfo.TileComponentInfo();
        while (iterator.hasNext()) {
            currentLine = iterator.next();
            if (currentLine.endsWith("}")) {
                break;
            } else {
                String[] tokens = currentLine.replace("\t", "").split(", ");
                for (String token : tokens) {
                    if (token.contains("=")) {
                        values.put(token.substring(0, token.indexOf("=")).trim(), token.substring(token.indexOf("=") + 1));
                    }
                }
            }
        }
        for (String key : values.keySet()) {
            switch (key) {
                case "csty":
                    tcInfo.setCsty(values.get(key));
                    break;
                case "numresolutions":
                    tcInfo.setNumResolutions(Integer.parseInt(values.get(key)));
                    break;
                case "cblkw":
                    String[] sValues = values.get(key).split("\\^");
                    tcInfo.setCodeBlockWidth((int) Math.pow(Integer.parseInt(sValues[0]), Integer.parseInt(sValues[1])));
                    break;
                case "cblkh":
                    sValues = values.get(key).split("\\^");
                    tcInfo.setCodeBlockHeight((int) Math.pow(Integer.parseInt(sValues[0]), Integer.parseInt(sValues[1])));
                    break;
                case "cblksty":
                    tcInfo.setCodeBlockSty(Integer.parseInt(values.get(key)));
                    break;
                case "qmfbid":
                    tcInfo.setQmfbid(Integer.parseInt(values.get(key)));
                    break;
                case "preccintsize (w,h)":
                    String[] pairs = values.get(key).replace("(", "").replace(")", "").split(" ");
                    for (String pair : pairs) {
                        tcInfo.addPreccInt(Integer.parseInt(pair.substring(0, pair.indexOf(","))),
                                           Integer.parseInt(pair.substring(pair.indexOf(",") + 1)));
                    }
                    break;
                case "qntsty":
                    tcInfo.setQntsty(values.get(key));
                    break;
                case "numgbits":
                    tcInfo.setNumGBits(Integer.parseInt(values.get(key)));
                    break;
                case "stepsizes (m,e)":
                    pairs = values.get(key).replace("(", "").replace(")", "").split(" ");
                    for (String pair : pairs) {
                        tcInfo.addStepSize(Integer.parseInt(pair.substring(0, pair.indexOf(","))),
                                Integer.parseInt(pair.substring(pair.indexOf(",") + 1)));
                    }
                    break;
                case "roishift":
                    tcInfo.setRoiShift(Integer.parseInt(values.get(key)));
                    break;
            }
        }
        this.codeStreamInfo.addComponentTileInfo(tcInfo);
    }


}
