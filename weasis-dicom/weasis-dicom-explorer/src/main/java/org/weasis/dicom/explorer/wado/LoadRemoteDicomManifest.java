/*******************************************************************************
 * Copyright (c) 2010 Nicolas Roduit.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Nicolas Roduit - initial API and implementation
 ******************************************************************************/
package org.weasis.dicom.explorer.wado;

import java.io.File;
import java.io.Serializable;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.swing.SwingWorker;

import org.weasis.core.api.explorer.model.DataExplorerModel;
import org.weasis.core.api.media.data.TagW;
import org.weasis.core.api.service.BundleTools;
import org.weasis.dicom.codec.DicomSeries;
import org.weasis.dicom.explorer.DicomModel;

public class LoadRemoteDicomManifest extends SwingWorker<Boolean, String> {

    public static final String CODOWNLOAD_SERIES_NB = "wado.codownload.series.nb"; //$NON-NLS-1$
    public static final BlockingQueue<Runnable> loadingQueue = new PriorityBlockingQueue<Runnable>(10,
        new PriorityTaskComparator());
    public static final ThreadPoolExecutor executor =
        new ThreadPoolExecutor(BundleTools.SYSTEM_PREFERENCES.getIntProperty(CODOWNLOAD_SERIES_NB, 3),
            BundleTools.SYSTEM_PREFERENCES.getIntProperty(CODOWNLOAD_SERIES_NB, 3), 0L, TimeUnit.MILLISECONDS,
            loadingQueue);
    public static final ArrayList<LoadSeries> currentTasks = new ArrayList<LoadSeries>();
    private final String[] xmlFiles;
    private final DicomModel dicomModel;

    private static class PriorityTaskComparator implements Comparator<Runnable>, Serializable {

        private static final long serialVersionUID = 513213203958362767L;

        @Override
        public int compare(final Runnable r1, final Runnable r2) {
            LoadSeries o1 = (LoadSeries) r1;
            LoadSeries o2 = (LoadSeries) r2;
            DownloadPriority val1 = o1.getPriority();
            DownloadPriority val2 = o2.getPriority();

            int rep = val1.getPriority().compareTo(val2.getPriority());
            if (rep != 0)
                return rep;
            rep = val1.getPatientName().compareTo(val2.getPatientName());
            if (rep != 0)
                return rep;
            if (val1.getStudyDate() != null && val2.getStudyDate() != null) {
                // inverse time
                rep = val2.getStudyDate().compareTo(val1.getStudyDate());
                if (rep != 0)
                    return rep;
            }
            rep = val1.getStudyInstanceUID().compareTo(val2.getStudyInstanceUID());
            if (rep != 0)
                return rep;
            rep = val1.getSeriesNumber().compareTo(val2.getSeriesNumber());
            if (rep != 0)
                return rep;
            String s1 = (String) o1.getDicomSeries().getTagValue(TagW.SubseriesInstanceUID);
            String s2 = (String) o2.getDicomSeries().getTagValue(TagW.SubseriesInstanceUID);
            return s1.compareTo(s2);
        }
    }

    public LoadRemoteDicomManifest(String[] xmlFiles, DataExplorerModel explorerModel) {
        if (xmlFiles == null || !(explorerModel instanceof DicomModel))
            throw new IllegalArgumentException("invalid parameters"); //$NON-NLS-1$
        this.xmlFiles = xmlFiles;
        this.dicomModel = (DicomModel) explorerModel;
    }

    public LoadRemoteDicomManifest(File[] xmlFiles, DataExplorerModel explorerModel) {
        if (xmlFiles == null || !(explorerModel instanceof DicomModel))
            throw new IllegalArgumentException("invalid parameters"); //$NON-NLS-1$
        String[] xmlRef = new String[xmlFiles.length];
        for (int i = 0; i < xmlFiles.length; i++) {
            if (xmlFiles[i] != null) {
                xmlRef[i] = xmlFiles[i].getAbsolutePath();
            }
        }
        this.xmlFiles = xmlRef;
        this.dicomModel = (DicomModel) explorerModel;
    }

    @Override
    protected Boolean doInBackground() throws Exception {
        for (int i = 0; i < xmlFiles.length; i++) {
            if (xmlFiles[i] != null) {
                URI uri = null;
                try {
                    if (!xmlFiles[i].startsWith("http")) { //$NON-NLS-1$
                        try {
                            File file = new File(xmlFiles[i]);
                            if (file.canRead()) {
                                uri = file.toURI();
                            }
                        } catch (Exception e) {
                        }
                    }
                    if (uri == null) {
                        uri = new URL(xmlFiles[i]).toURI();
                    }
                    ArrayList<LoadSeries> wadoTasks = DownloadManager.buildDicomSeriesFromXml(uri, dicomModel);
                    if (wadoTasks != null) {
                        for (LoadSeries s : wadoTasks) {
                            loadingQueue.offer(s);
                        }
                    }
                } catch (URISyntaxException e) {
                    e.printStackTrace();
                } catch (MalformedURLException e) {
                    e.printStackTrace();
                }
            }
        }

        Runnable[] tasks = loadingQueue.toArray(new Runnable[loadingQueue.size()]);
        for (int i = 0; i < tasks.length; i++) {
            LoadSeries l = (LoadSeries) tasks[i];
            if (!currentTasks.contains(l)) {
                currentTasks.add(l);
            }
        }
        executor.prestartAllCoreThreads();

        return true;
    }

    @Override
    protected void done() {
    }

    public static void stopDownloading(DicomSeries series) {
        if (series != null) {
            for (final LoadSeries loading : LoadRemoteDicomManifest.currentTasks) {
                if (loading.getDicomSeries() == series) {
                    LoadRemoteDicomManifest.currentTasks.remove(loading);
                    LoadRemoteDicomManifest.loadingQueue.remove(loading);
                    if (StateValue.STARTED.equals(loading.getState())) {
                        loading.cancel(true);
                    }
                    // Ensure to stop downloading
                    series.setSeriesLoader(null);
                    break;
                }
            }
        }
    }
}