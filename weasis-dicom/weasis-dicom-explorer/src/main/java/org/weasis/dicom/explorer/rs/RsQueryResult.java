package org.weasis.dicom.explorer.rs;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import javax.json.Json;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.json.JSONReader;
import org.dcm4che3.json.JSONReader.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.media.data.MediaSeriesGroup;
import org.weasis.core.api.media.data.MediaSeriesGroupNode;
import org.weasis.core.api.media.data.Series;
import org.weasis.core.api.media.data.TagW;
import org.weasis.core.api.service.BundleTools;
import org.weasis.core.api.util.ClosableURLConnection;
import org.weasis.core.api.util.LangUtil;
import org.weasis.core.api.util.NetworkUtil;
import org.weasis.core.api.util.StringUtil;
import org.weasis.dicom.codec.DicomSeries;
import org.weasis.dicom.codec.TagD;
import org.weasis.dicom.codec.TagD.Level;
import org.weasis.dicom.codec.utils.DicomMediaUtils;
import org.weasis.dicom.codec.utils.PatientComparator;
import org.weasis.dicom.explorer.DicomModel;
import org.weasis.dicom.explorer.wado.DownloadPriority;
import org.weasis.dicom.explorer.wado.LoadSeries;
import org.weasis.dicom.explorer.wado.SeriesInstanceList;
import org.weasis.dicom.mf.AbstractQueryResult;
import org.weasis.dicom.mf.SopInstance;
import org.weasis.dicom.mf.WadoParameters;
import org.weasis.dicom.util.DateUtil;
import org.weasis.dicom.web.Multipart;

public class RsQueryResult extends AbstractQueryResult {
    private static final Logger LOGGER = LoggerFactory.getLogger(RsQueryResult.class);

    private static final String STUDY_QUERY =
        "&includefield=00080020,00080030,00080050,00080061,00080090,00081030,00100010,00100020,00100021,00100030,00100040,0020000D,00200010";
    private static final String QIDO_REQUEST = "QIDO-RS request: {}";

    private final RsQueryParams rsQueryParams;
    private final WadoParameters wadoParameters;

    public RsQueryResult(RsQueryParams rsQueryParams) {
        this.rsQueryParams = rsQueryParams;
        this.wadoParameters = new WadoParameters("", true, true); //$NON-NLS-1$
        rsQueryParams.getRetrieveHeaders().forEach(wadoParameters::addHttpTag);
        // Accept only multipart/related and retrieve dicom at the stored syntax
        wadoParameters.addHttpTag("Accept", Multipart.MULTIPART_RELATED + ";type=" + Multipart.ContentType.DICOM + ";transfer-syntax=*");
    }

    @Override
    public WadoParameters getWadoParameters() {
        return null;
    }

    public void buildFromPatientID(List<String> patientIDs) {
        for (String patientID : LangUtil.emptyIfNull(patientIDs)) {
            if (!StringUtil.hasText(patientID)) {
                continue;
            }

            // IssuerOfPatientID filter ( syntax like in HL7 with extension^^^root)
            int beginIndex = patientID.indexOf("^^^");

            StringBuilder buf = new StringBuilder(rsQueryParams.getBaseUrl());
            buf.append("/studies?00100020=");
            buf.append(beginIndex <= 0 ? patientID : patientID.substring(0, beginIndex));
            if (beginIndex > 0) {
                buf.append("&00100021=");
                buf.append(patientID.substring(beginIndex + 3));
            }
            buf.append(STUDY_QUERY);
            buf.append(rsQueryParams.getProperties().getProperty(RsQueryParams.P_QUERY_EXT, ""));

            try {
                LOGGER.debug(QIDO_REQUEST, buf);
                List<Attributes> studies = parseJSON(buf.toString());
                if (!studies.isEmpty()) {
                    Collections.sort(studies, getStudyComparator());
                    applyAllFilters(studies);
                }
            } catch (Exception e) {
                LOGGER.error("QIDO-RS with PatientID {}", patientID, e);
            }
        }
    }

    private List<Attributes> parseJSON(String url) throws IOException {
        List<Attributes> items = new ArrayList<>();
        try (ClosableURLConnection httpCon =
            NetworkUtil.getUrlConnection(new URL(url), rsQueryParams.getQueryHeaders());
                        InputStreamReader instream =
                            new InputStreamReader(httpCon.getInputStream(), StandardCharsets.UTF_8)) {
            JSONReader reader = new JSONReader(Json.createParser(instream));
            Callback callback = (fmi, dataset) -> items.add(dataset);
            reader.readDatasets(callback);
        }
        return items;
    }

    private void applyAllFilters(List<Attributes> studies) {
        if (StringUtil.hasText(rsQueryParams.getLowerDateTime())) {
            Date lowerDateTime = null;
            try {
                lowerDateTime = DateUtil.parseXmlDateTime(rsQueryParams.getLowerDateTime()).getTime();
            } catch (Exception e) {
                LOGGER.error("Cannot parse date: {}", rsQueryParams.getLowerDateTime(), e);
            }
            if (lowerDateTime != null) {
                for (int i = studies.size() - 1; i >= 0; i--) {
                    Attributes s = studies.get(i);
                    Date date = s.getDate(Tag.StudyDateAndTime);
                    if (date != null) {
                        int rep = date.compareTo(lowerDateTime);
                        if (rep > 0) {
                            studies.remove(i);
                        }
                    }
                }
            }
        }

        if (StringUtil.hasText(rsQueryParams.getUpperDateTime())) {
            Date upperDateTime = null;
            try {
                upperDateTime = DateUtil.parseXmlDateTime(rsQueryParams.getUpperDateTime()).getTime();
            } catch (Exception e) {
                LOGGER.error("Cannot parse date: {}", rsQueryParams.getUpperDateTime(), e);
            }
            if (upperDateTime != null) {
                for (int i = studies.size() - 1; i >= 0; i--) {
                    Attributes s = studies.get(i);
                    Date date = s.getDate(Tag.StudyDateAndTime);
                    if (date != null) {
                        int rep = date.compareTo(upperDateTime);
                        if (rep < 0) {
                            studies.remove(i);
                        }
                    }
                }
            }
        }

        if (StringUtil.hasText(rsQueryParams.getMostRecentResults())) {
            int recent = StringUtil.getInteger(rsQueryParams.getMostRecentResults());
            if (recent > 0) {
                for (int i = studies.size() - 1; i >= recent; i--) {
                    studies.remove(i);
                }
            }
        }

        if (StringUtil.hasText(rsQueryParams.getModalitiesInStudy())) {
            for (int i = studies.size() - 1; i >= 0; i--) {
                Attributes s = studies.get(i);
                String m = s.getString(Tag.ModalitiesInStudy);
                if (StringUtil.hasText(m)) {
                    boolean remove = true;
                    for (String mod : rsQueryParams.getModalitiesInStudy().split(",")) {
                        if (m.indexOf(mod) != -1) {
                            remove = false;
                            break;
                        }
                    }

                    if (remove) {
                        studies.remove(i);
                    }
                }
            }

        }

        if (StringUtil.hasText(rsQueryParams.getKeywords())) {
            String[] keys = rsQueryParams.getKeywords().split(",");
            for (int i = 0; i < keys.length; i++) {
                keys[i] = StringUtil.deAccent(keys[i].trim().toUpperCase());
            }

            studyLabel: for (int i = studies.size() - 1; i >= 0; i--) {
                Attributes s = studies.get(i);
                String desc = StringUtil.deAccent(s.getString(Tag.StudyDescription, "").toUpperCase());

                for (int j = 0; j < keys.length; j++) {
                    if (desc.contains(keys[j])) {
                        continue studyLabel;
                    }
                }
                studies.remove(i);
            }
        }

        for (Attributes studyDataSet : studies) {
            fillSeries(studyDataSet);
        }
    }

    private static Comparator<Attributes> getStudyComparator() {
        return (o1, o2) -> {
            Date date1 = o1.getDate(Tag.StudyDate);
            Date date2 = o2.getDate(Tag.StudyDate);
            if (date1 != null && date2 != null) {
                // inverse time
                int rep = date2.compareTo(date1);
                if (rep == 0) {
                    Date time1 = o1.getDate(Tag.StudyTime);
                    Date time2 = o2.getDate(Tag.StudyTime);
                    if (time1 != null && time2 != null) {
                        // inverse time
                        return time2.compareTo(time1);
                    }
                } else {
                    return rep;
                }
            }
            if (date1 == null && date2 == null) {
                return o1.getString(Tag.StudyInstanceUID, "").compareTo(o2.getString(Tag.StudyInstanceUID, ""));
            } else {
                if (date1 == null) {
                    return 1;
                }
                if (date2 == null) {
                    return -1;
                }
            }
            return 0;
        };
    }

    public void buildFromStudyInstanceUID(List<String> studyInstanceUIDs) {
        for (String studyInstanceUID : LangUtil.emptyIfNull(studyInstanceUIDs)) {
            if (!StringUtil.hasText(studyInstanceUID)) {
                continue;
            }
            StringBuilder buf = new StringBuilder(rsQueryParams.getBaseUrl());
            buf.append("/studies?0020000D=");
            buf.append(studyInstanceUID);
            buf.append(STUDY_QUERY);
            buf.append(rsQueryParams.getProperties().getProperty(RsQueryParams.P_QUERY_EXT, ""));

            try {
                LOGGER.debug(QIDO_REQUEST, buf);
                List<Attributes> studies = parseJSON(buf.toString());
                for (Attributes studyDataSet : studies) {
                    fillSeries(studyDataSet);
                }
            } catch (Exception e) {
                LOGGER.error("QIDO-RS with studyUID {}", studyInstanceUID, e);
            }
        }
    }

    public void buildFromStudyAccessionNumber(List<String> accessionNumbers) {
        for (String accessionNumber : LangUtil.emptyIfNull(accessionNumbers)) {
            if (!StringUtil.hasText(accessionNumber)) {
                continue;
            }
            StringBuilder buf = new StringBuilder(rsQueryParams.getBaseUrl());
            buf.append("/studies?00080050=");
            buf.append(accessionNumber);
            buf.append(STUDY_QUERY);
            buf.append(rsQueryParams.getProperties().getProperty(RsQueryParams.P_QUERY_EXT, ""));

            try {
                LOGGER.debug(QIDO_REQUEST, buf);
                List<Attributes> studies = parseJSON(buf.toString());
                for (Attributes studyDataSet : studies) {
                    fillSeries(studyDataSet);
                }
            } catch (Exception e) {
                LOGGER.error("QIDO-RS with AccessionNumber {}", accessionNumber, e);
            }
        }
    }

    public void buildFromSeriesInstanceUID(List<String> seriesInstanceUIDs) {
        for (String seriesInstanceUID : LangUtil.emptyIfNull(seriesInstanceUIDs)) {
            if (!StringUtil.hasText(seriesInstanceUID)) {
                continue;
            }

            StringBuilder buf = new StringBuilder(rsQueryParams.getBaseUrl());
            buf.append("/series?0020000E=");
            buf.append(seriesInstanceUID);
            buf.append(STUDY_QUERY);
            buf.append(",0008103E,00080060,00081190,00200011");
            buf.append(rsQueryParams.getProperties().getProperty(RsQueryParams.P_QUERY_EXT, ""));

            try {
                LOGGER.debug(QIDO_REQUEST, buf);
                List<Attributes> series = parseJSON(buf.toString());
                if (!series.isEmpty()) {
                    Attributes dataset = series.get(0);
                    MediaSeriesGroup patient = getPatient(dataset);
                    MediaSeriesGroup study = getStudy(patient, dataset);
                    for (Attributes seriesDataset : series) {
                        Series<?> dicomSeries = getSeries(study, seriesDataset);
                        fillInstance(seriesDataset, study, dicomSeries);
                    }
                }
            } catch (Exception e) {
                LOGGER.error("QIDO-RS with seriesUID {}", seriesInstanceUID, e);
            }
        }
    }

    public void buildFromSopInstanceUID(List<String> sopInstanceUIDs) {
        for (String sopInstanceUID : LangUtil.emptyIfNull(sopInstanceUIDs)) {
            if (!StringUtil.hasText(sopInstanceUID)) {
                continue;
            }

            StringBuilder buf = new StringBuilder(rsQueryParams.getBaseUrl());
            buf.append("/instances?00080018=");
            buf.append(sopInstanceUID);
            buf.append(STUDY_QUERY);
            buf.append(",0008103E,00080060,0020000E,00200011");
            buf.append(",00200013,00081190");
            buf.append(rsQueryParams.getProperties().getProperty(RsQueryParams.P_QUERY_EXT, ""));

            try {
                LOGGER.debug(QIDO_REQUEST, buf);
                List<Attributes> instances = parseJSON(buf.toString());
                if (!instances.isEmpty()) {
                    Attributes dataset = instances.get(0);
                    MediaSeriesGroup patient = getPatient(dataset);
                    MediaSeriesGroup study = getStudy(patient, dataset);
                    Series<?> dicomSeries = getSeries(study, dataset);
                    SeriesInstanceList seriesInstanceList =
                        (SeriesInstanceList) dicomSeries.getTagValue(TagW.WadoInstanceReferenceList);
                    if (seriesInstanceList != null) {
                        for (Attributes instanceDataSet : instances) {
                            String sopUID = instanceDataSet.getString(Tag.SOPInstanceUID);
                            Integer frame =
                                DicomMediaUtils.getIntegerFromDicomElement(instanceDataSet, Tag.InstanceNumber, null);

                            SopInstance sop = seriesInstanceList.getSopInstance(sopUID, frame);
                            if (sop == null) {
                                sop = new SopInstance(sopUID, frame);
                                sop.setDirectDownloadFile(instanceDataSet.getString(Tag.RetrieveURL));
                                seriesInstanceList.addSopInstance(sop);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.error("QIDO-RS with sopInstanceUID {}", sopInstanceUID, e);
            }
        }
    }

    private void fillSeries(Attributes studyDataSet) {
        String studyInstanceUID = studyDataSet.getString(Tag.StudyInstanceUID);
        if (StringUtil.hasText(studyInstanceUID)) {
            StringBuilder buf = new StringBuilder(rsQueryParams.getBaseUrl());
            buf.append("/studies/");
            buf.append(studyInstanceUID);
            buf.append("/series?includefield=");
            buf.append("0008103E,00080060,0020000E,00200011,00081190");
            buf.append(rsQueryParams.getProperties().getProperty(RsQueryParams.P_QUERY_EXT, ""));

            try {
                LOGGER.debug(QIDO_REQUEST, buf);
                List<Attributes> series = parseJSON(buf.toString());
                if (!series.isEmpty()) {
                    // Get patient from each study in case IssuerOfPatientID is different
                    MediaSeriesGroup patient = getPatient(studyDataSet);
                    MediaSeriesGroup study = getStudy(patient, studyDataSet);
                    for (Attributes seriesDataset : series) {
                        Series<?> dicomSeries = getSeries(study, seriesDataset);
                        fillInstance(seriesDataset, study, dicomSeries);
                    }
                }
            } catch (Exception e) {
                LOGGER.error("QIDO-RS all series with studyUID {}", studyInstanceUID, e);
            }
        }
    }

    private void fillInstance(Attributes seriesDataset, MediaSeriesGroup study, Series<?> dicomSeries) {
        String serieInstanceUID = seriesDataset.getString(Tag.SeriesInstanceUID);
        if (StringUtil.hasText(serieInstanceUID)) {
            StringBuilder buf = new StringBuilder(rsQueryParams.getBaseUrl());
            buf.append("/studies/");
            buf.append(study.getTagValue(TagD.get(Tag.StudyInstanceUID)));
            buf.append("/series/");
            buf.append(serieInstanceUID);
            buf.append("/instances?includefield=");
            buf.append("00080018,00200013,00081190");
            buf.append(rsQueryParams.getProperties().getProperty(RsQueryParams.P_QUERY_EXT, ""));

            try {
                LOGGER.debug(QIDO_REQUEST, buf);
                List<Attributes> instances = parseJSON(buf.toString());
                if (!instances.isEmpty()) {
                    SeriesInstanceList seriesInstanceList =
                        (SeriesInstanceList) dicomSeries.getTagValue(TagW.WadoInstanceReferenceList);
                    if (seriesInstanceList != null) {
                        for (Attributes instanceDataSet : instances) {
                            String sopUID = instanceDataSet.getString(Tag.SOPInstanceUID);
                            Integer frame =
                                DicomMediaUtils.getIntegerFromDicomElement(instanceDataSet, Tag.InstanceNumber, null);

                            SopInstance sop = seriesInstanceList.getSopInstance(sopUID, frame);
                            if (sop == null) {
                                sop = new SopInstance(sopUID, frame);
                                sop.setDirectDownloadFile(instanceDataSet.getString(Tag.RetrieveURL));
                                seriesInstanceList.addSopInstance(sop);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.error("QIDO-RS all instances with seriesUID {}", serieInstanceUID, e);
            }
        }
    }

    private MediaSeriesGroup getPatient(Attributes patientDataset) {
        if (patientDataset == null) {
            throw new IllegalArgumentException("patientDataset cannot be null");
        }

        PatientComparator patientComparator = new PatientComparator(patientDataset);
        String patientPseudoUID = patientComparator.buildPatientPseudoUID();

        DicomModel model = rsQueryParams.getDicomModel();
        MediaSeriesGroup patient = model.getHierarchyNode(MediaSeriesGroupNode.rootNode, patientPseudoUID);
        if (patient == null) {
            patient =
                new MediaSeriesGroupNode(TagD.getUID(Level.PATIENT), patientPseudoUID, DicomModel.patient.getTagView());
            patient.setTag(TagD.get(Tag.PatientID), patientComparator.getPatientId());
            patient.setTag(TagD.get(Tag.PatientName), patientComparator.getName());
            patient.setTagNoNull(TagD.get(Tag.IssuerOfPatientID), patientComparator.getIssuerOfPatientID());

            TagW[] tags = TagD.getTagFromIDs(Tag.PatientSex, Tag.PatientBirthDate, Tag.PatientBirthTime);
            for (TagW tag : tags) {
                tag.readValue(patientDataset, patient);
            }

            model.addHierarchyNode(MediaSeriesGroupNode.rootNode, patient);
            LOGGER.info("Adding new patient: {}", patient); //$NON-NLS-1$
        }
        return patient;
    }

    private MediaSeriesGroup getStudy(MediaSeriesGroup patient, final Attributes studyDataset) {
        if (studyDataset == null) {
            throw new IllegalArgumentException("studyDataset cannot be null");
        }
        String studyUID = studyDataset.getString(Tag.StudyInstanceUID);
        DicomModel model = rsQueryParams.getDicomModel();
        MediaSeriesGroup study = model.getHierarchyNode(patient, studyUID);
        if (study == null) {
            study = new MediaSeriesGroupNode(TagD.getUID(Level.STUDY), studyUID, DicomModel.study.getTagView());
            TagW[] tags = TagD.getTagFromIDs(Tag.StudyDate, Tag.StudyTime, Tag.StudyDescription, Tag.AccessionNumber,
                Tag.StudyID, Tag.ReferringPhysicianName);
            for (TagW tag : tags) {
                tag.readValue(studyDataset, study);
            }

            model.addHierarchyNode(patient, study);
        }
        return study;
    }

    private Series getSeries(MediaSeriesGroup study, final Attributes seriesDataset) {
        if (seriesDataset == null) {
            throw new IllegalArgumentException("seriesDataset cannot be null");
        }
        String seriesUID = seriesDataset.getString(Tag.SeriesInstanceUID);
        DicomModel model = rsQueryParams.getDicomModel();
        Series dicomSeries = (Series) model.getHierarchyNode(study, seriesUID);
        if (dicomSeries == null) {
            dicomSeries = new DicomSeries(seriesUID);
            dicomSeries.setTag(TagD.get(Tag.SeriesInstanceUID), seriesUID);
            dicomSeries.setTag(TagW.ExplorerModel, model);
            dicomSeries.setTag(TagW.WadoParameters, wadoParameters);
            dicomSeries.setTag(TagW.WadoInstanceReferenceList, new SeriesInstanceList());

            TagW[] tags = TagD.getTagFromIDs(Tag.Modality, Tag.SeriesNumber, Tag.SeriesDescription, Tag.RetrieveURL);
            for (TagW tag : tags) {
                tag.readValue(seriesDataset, dicomSeries);
            }
            model.addHierarchyNode(study, dicomSeries);

            final LoadSeries loadSeries = new LoadSeries(dicomSeries, rsQueryParams.getDicomModel(),
                BundleTools.SYSTEM_PREFERENCES.getIntProperty(LoadSeries.CONCURRENT_DOWNLOADS_IN_SERIES, 4), true);
            loadSeries.setPriority(
                new DownloadPriority(model.getParent(study, DicomModel.patient), study, dicomSeries, true));
            rsQueryParams.getSeriesMap().put(TagD.getTagValue(dicomSeries, Tag.SeriesInstanceUID, String.class),
                loadSeries);
        }
        return dicomSeries;
    }
}