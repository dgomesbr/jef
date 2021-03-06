/* Copyright 2010-2017 Norconex Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.norconex.jef4.log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import org.apache.log4j.Appender;
import org.apache.log4j.FileAppender;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;

import com.norconex.commons.lang.config.XMLConfigurationUtil;
import com.norconex.commons.lang.file.FileUtil;
import com.norconex.commons.lang.io.FilteredInputStream;
import com.norconex.commons.lang.io.IInputStreamFilter;
import com.norconex.jef4.JEFException;
import com.norconex.jef4.JEFUtil;

/**
 * <p>
 * Log manager using the file system to store its logs.
 * When no log directory is explicitly set, it defaults to: 
 * <code>&lt;user.home&gt;/Norconex/jef/workdir</code>
 * </p>
 * 
 * <h3>XML configuration usage:</h3>
 * <pre>
 *  &lt;logManager class="com.norconex.jef4.log.FileLogManager"&gt;
 *      &lt;logDir&gt;(directory where to store logs)&lt;/logDir&gt;
 *  &lt;/logManager&gt;
 * </pre>
 * <h4>Usage example:</h4>
 * <p>
 * The following example indicates logs should be stored in this directory:
 * <code>/tmp/jeflogs</code>
 * </p>
 * <pre>
 *  &lt;logManager class="com.norconex.jef4.log.FileLogManager"&gt;
 *      &lt;logDir&gt;/tmp/jeflogs&lt;/logDir&gt;
 *  &lt;/logManager&gt;
 * </pre>
 * 
 * @author Pascal Essiembre
 */
public class FileLogManager implements ILogManager {

    private static final Logger LOG =
            LogManager.getLogger(FileLogManager.class);
    
    private static final String LAYOUT_PATTERN = 
            "%d{yyyy-MM-dd HH:mm:ss} %p - %m\n";
    
    /** Directory where to store the file. */
    private String logdirLatest;
    /** Directory where to backup the file. */
    private String logdirBackupBase;
    /** Base parent log directory. */
    private String logdir;
    
    private static final String LOG_SUFFIX = ".log";
    
    private boolean needToResolveDirs= true;
    
    /**
     * Constructor using default log directory location.
     */
    public FileLogManager() {
        this(null);
    }

    /**
     * Creates a new <code>FileLogManager</code>, wrapping the given
     * layout into a <code>ThreadSafeLayout</code>.
     * @param logdir base directory where the log should be stored
     */
    public FileLogManager(final String logdir) {
        super();
        this.logdir = logdir;
        this.needToResolveDirs = true;
    }

    public String getLogDirectory() {
        return logdir;
    }
    public void setLogDirectory(String logdir) {
        this.logdir = logdir;
        this.needToResolveDirs = true;
    }
    
    private synchronized void resolveDirsIfNeeded() {
        // Leave now if we do not need to update dirs.
        if (!needToResolveDirs) {
            return;
        }

        // Log dir changed, update dirs
        String path = logdir;
        if (StringUtils.isBlank(logdir)) {
            path = JEFUtil.FALLBACK_WORKDIR.getAbsolutePath();
        } else {
            path = new File(path).getAbsolutePath();
        }
        
        LOG.debug("Log directory: " + path); 
        logdirLatest = path + File.separatorChar 
                + "latest" + File.separatorChar + "logs";
        logdirBackupBase = path + "/backup";
        File dir = new File(logdirLatest);
        if (!dir.exists()) {
            if (!dir.exists()) {
                try {
                    FileUtils.forceMkdir(dir);
                } catch (IOException e) {
                    throw new JEFException("Cannot create log directory: "
                            + logdirLatest, e);
                }
            }  
        }
        this.needToResolveDirs = false;
    }
    
    @Override
    public final Appender createAppender(final String suiteId)
            throws IOException {
        resolveDirsIfNeeded();
        return new FileAppender(new PatternLayout(LAYOUT_PATTERN),
                logdirLatest + "/" + 
                        FileUtil.toSafeFileName(suiteId) + LOG_SUFFIX);
    }
    
    @Override
    public final void backup(final String suiteId, final Date backupDate)
            throws IOException {
        resolveDirsIfNeeded();
        String date = new SimpleDateFormat(
                "yyyyMMddHHmmssSSSS").format(backupDate);
        File progressFile = getLogFile(suiteId);
        

        File backupDir = FileUtil.createDateDirs(
                new File(logdirBackupBase), backupDate);
        backupDir = new File(backupDir, "logs");
        if (!backupDir.exists()) {
            try {
                FileUtils.forceMkdir(backupDir);
            } catch (IOException e) {
                throw new JEFException("Cannot create backup directory: "
                        + backupDir, e);
            }
        }        
        File backupFile = new File(
                backupDir + "/" + date + "__" 
                        + FileUtil.toSafeFileName(suiteId) + LOG_SUFFIX);
        if (progressFile.exists()) {
            FileUtil.moveFile(progressFile, backupFile);
        }
    }

    @Override
    public final InputStream getLog(final String suiteId) throws IOException {
        File logFile = getLogFile(suiteId);
        if (logFile != null && logFile.exists()) {
            return new FileInputStream(logFile);
        }
        return null;
    }
    @Override
    public InputStream getLog(String suiteId, String jobId) throws IOException {
        if (jobId == null) {
            return getLog(suiteId);
        }
        InputStream fullLog = getLog(suiteId);
        if (fullLog != null) {
            return new FilteredInputStream(
                    getLog(suiteId), new StartWithFilter(jobId));
        }
        return null;
    }

    /**
     * Gets the log file used by this log manager.
     * @param suiteId log file suiteId
     * @return log file
     */
    public File getLogFile(final String suiteId) {
        if (suiteId == null) {
            return null;
        }
        resolveDirsIfNeeded();
        return new File(logdirLatest + "/" 
                + FileUtil.toSafeFileName(suiteId) + LOG_SUFFIX);
    }
    
    @Override
    public void loadFromXML(Reader in) throws IOException {
        XMLConfiguration xml = XMLConfigurationUtil.newXMLConfiguration(in);
        setLogDirectory(xml.getString("logDir", logdir));
    }

    @Override
    public void saveToXML(Writer out) throws IOException {
        XMLOutputFactory factory = XMLOutputFactory.newInstance();
        try {
            XMLStreamWriter writer = factory.createXMLStreamWriter(out);
            writer.writeStartElement("logManager");
            writer.writeAttribute("class", getClass().getCanonicalName());
            writer.writeStartElement("logDir");
            writer.writeCharacters(new File(logdir).getAbsolutePath());
            writer.writeEndElement();
            writer.writeEndElement();
            writer.flush();
            writer.close();
        } catch (XMLStreamException e) {
            throw new IOException("Cannot save as XML.", e);
        }       
    }

    private static class StartWithFilter implements IInputStreamFilter {
        private final String startsWith;
        public StartWithFilter(String startsWith) {
            super();
            this.startsWith = startsWith;
        }
        @Override
        public boolean accept(String line) {
            return line.startsWith(startsWith + ":");
        }
    }

    @Override
    public boolean equals(final Object other) {
        if (!(other instanceof FileLogManager)) {
            return false;
        }
        FileLogManager castOther = (FileLogManager) other;
        return new EqualsBuilder()
                .append(logdir, castOther.logdir)
                .isEquals();
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder()
                .append(logdir)
                .toHashCode();
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.SHORT_PREFIX_STYLE)
                .appendSuper(super.toString())
                .append("logDir", logdir)
                .toString();
    }
}
