package org.juxtasoftware.resource;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.commons.io.IOUtils;
import org.juxtasoftware.dao.AlignmentDao;
import org.juxtasoftware.dao.ComparisonSetDao;
import org.juxtasoftware.dao.WitnessDao;
import org.juxtasoftware.model.Alignment;
import org.juxtasoftware.model.Alignment.AlignedAnnotation;
import org.juxtasoftware.model.AlignmentConstraint;
import org.juxtasoftware.model.ComparisonSet;
import org.juxtasoftware.model.QNameFilter;
import org.juxtasoftware.model.Witness;
import org.juxtasoftware.util.QNameFilters;
import org.restlet.data.Status;
import org.restlet.representation.Representation;
import org.restlet.resource.Get;
import org.restlet.resource.ResourceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import eu.interedition.text.Range;

/**
 * Resource used to export sets in various formats. 
 *  
 * @author lfoster
 *
 */
@Service
@Scope(BeanDefinition.SCOPE_PROTOTYPE)
public class Exporter extends BaseResource {
    @Autowired private ComparisonSetDao setDao;
    @Autowired private WitnessDao witnessDao;
    @Autowired private QNameFilters qnameFilters;
    @Autowired private AlignmentDao alignmentDao;
    private ComparisonSet set;
    private Witness base;

    @Override
    protected void doInit() throws ResourceException {
        super.doInit();
        
        Long setId = getIdFromAttributes("id");
        if ( setId == null ) {
            return;
        }
        this.set = this.setDao.find(setId);
        if ( validateModel(this.set) == false ) {
            return;
        }
        
        if (getQuery().getValuesMap().containsKey("mode") ) {
            String mode = getQuery().getValuesMap().get("mode").toLowerCase();
            if ( mode.equals("teips") == false ) {
                setStatus(Status.CLIENT_ERROR_BAD_REQUEST, "Unsupported export mode specified");
            }
        } else {
            setStatus(Status.CLIENT_ERROR_BAD_REQUEST, "Missing required mode parameter");
        }
        
        if (getQuery().getValuesMap().containsKey("base") ) {
            String idStr = getQuery().getValuesMap().get("base");
            Long id = null;
            try {
                id = Long.parseLong(idStr);
            } catch (NumberFormatException e) {
                setStatus(Status.CLIENT_ERROR_BAD_REQUEST, "Invalid base identifer specified");
            }
            
            this.base = this.witnessDao.find(id);
            if ( validateModel(this.base) == false ) {
                return;
            }
            
        } else {
            setStatus(Status.CLIENT_ERROR_BAD_REQUEST, "Missing required base parameter");
        }
    }
    
    @Get("xml")
    public Representation exportSet() {
        try {
            String template = IOUtils.toString(ClassLoader.getSystemResourceAsStream("templates/xml/teips.xml"));
            template = template.replace("$TITLE", this.set.getName());
            
            Set<Witness> witnesses = this.setDao.getWitnesses(this.set);
            
            final String listWit = generateListWitContent(witnesses);
            template = template.replace("$LISTWIT", listWit);
            
            File appFile = generateApparatus(witnesses);
            FileInputStream fis= new FileInputStream(appFile);
            String hack = IOUtils.toString(fis);
            System.err.println( hack );
            IOUtils.closeQuietly(fis);
            appFile.delete();
            
            template = template.replace("$BODY", hack);
            
            return toTextRepresentation(template);
        } catch (IOException e) {
            setStatus(Status.SERVER_ERROR_INTERNAL);
            return toTextRepresentation("Unable to export comparison set");
        }
        
    }

    private File generateApparatus(Set<Witness> witnesses) throws IOException {
        // Algo: stream text from the pase witness until a diff is found
        // at that point, inject an <app>. Each non-base witness content will be
        // added in <rdg> tags. The base version in a <lem> tag.
        // TODO this choice needs to be documented somewhere
        // TODO also note that the final output may not strictly adhere to TEI PS
        // output if things like ignore caps / punctuation are set. ie it could
        // show 'cat.' as the same as 'CAT!'. This should be documented somewhere.
        
        // setup readers/writers for the data
        File out = File.createTempFile("ps_app", "dat");
        out.deleteOnExit();
        FileOutputStream fos = new FileOutputStream(out); 
        OutputStream bout = new BufferedOutputStream(fos);
        OutputStreamWriter ow = new OutputStreamWriter(bout, "UTF-8");
        Reader witReader = this.witnessDao.getContentStream(this.base);
        ow.write("<p>");
        
        // get a batch of alignments to work with... when these are used up
        // another batch will be retrieved
        //final int alignBatchSize = 1000;
        //int alignsFrom = 0;
        QNameFilter changesFilter = this.qnameFilters.getDifferencesFilter();
        AlignmentConstraint constraint = new AlignmentConstraint(set, this.base.getId());
        constraint.setFilter(changesFilter);
        //constraint.setResultsRange(alignsFrom, alignBatchSize);
        List<Alignment> alignments = this.alignmentDao.list(constraint);
        //alignsFrom = alignBatchSize;
        
        List<AppData> appData = generateAppData(alignments);
        Iterator<AppData> itr = appData.iterator();
        
        // set the current align to first in the available list
        AppData currApp = null;
        if ( itr.hasNext() ) {
            currApp = itr.next();
        }
        
        long pos = 0;
        while ( true ) {
            int data = witReader.read();
            if ( data == -1 ) {
                break;
            }
            
            if ( currApp != null && pos == currApp.getBaseRange().getStart() ) {
                
                // write the app and lem tags followed by the just-read character
                ow.write("<app>\n");
                final String tag = String.format("<lem wit=\"#wit-%d\">", currApp.getBaseId());
                ow.write(tag);
                ow.write(data);
                pos++;
                
                // write out all of the text from the base witness within the lem tag
                while ( pos < currApp.getBaseRange().getEnd() ) {
                    data = witReader.read();
                    if ( data == -1 ) {
                        throw new IOException("invalid aligment: past end of document");
                    } else {
                        ow.write(data);
                        pos++;
                    }
                }
                
                // end the lem tag 
                ow.write("</lem>\n");
                
                // write witnesses
                for ( Entry<Long, Range> entry : currApp.getWitnessData().entrySet()) {
                    final String rdg = String.format("<rdg wit=\"#wit-%d\">", entry.getKey());
                    ow.write(rdg);
                    ow.write( getWitnessFragment(entry.getKey(), entry.getValue()) );
                    ow.write("</rdg>\n");
                }
                ow.write("</app>\n");
                
                // move on to the next annotation
                currApp = null;
                if ( itr.hasNext() ) {
                    currApp = itr.next();
                }
                
            } else {
                ow.write(data);
                pos++;
            }
        }
        
        ow.write("</p>");
        
        IOUtils.closeQuietly(ow);
        IOUtils.closeQuietly(witReader);
        return out;
    }
    
    private String getWitnessFragment(Long witId, Range range) throws IOException {
        Witness w = this.witnessDao.find(witId);
        Reader r = this.witnessDao.getContentStream(w);
        StringBuilder buff = new StringBuilder();
        long pos= 0;
        while ( true) {
            int data = r.read();
            if ( data == -1 ) {
                return buff.toString();
            }
            if ( pos >= range.getStart() && pos <range.getEnd()) {
                buff.append((char)data);
                if ( pos == range.getEnd()) {
                    return buff.toString();
                }
            }
            pos++;
        }
    }

    private List<AppData> generateAppData( List<Alignment> alignments ) {
        List<AppData> data = new ArrayList<Exporter.AppData>();
        Map<Range, AppData> changeMap = new HashMap<Range, AppData>();
        Iterator<Alignment> itr = alignments.iterator();
        while ( itr.hasNext() ) {
            Alignment align = itr.next();
            itr.remove();
            
            // get base and add it to list of found ranges or get
            // pre-existing data for that range
            AlignedAnnotation baseAnno = align.getWitnessAnnotation(this.base.getId());
            AppData appData = changeMap.get(baseAnno.getRange());
            if ( appData == null ) {
                appData= new AppData( this.base.getId(), baseAnno.getRange(), align.getGroup() );
                changeMap.put(baseAnno.getRange(), appData);
                data.add(appData);
            }
            
            // add witness data to the app info
            for ( AlignedAnnotation a : align.getAnnotations()) {
                if ( a.getWitnessId().equals( base.getId()) == false ) {
                    appData.addWitness(a.getWitnessId(), a.getRange());
                    break;
                }
            }
        }
        
        // take a pass thru the data and merge items with same group id
        Iterator<AppData> appItr = data.iterator();
        AppData prior = null;
        while ( appItr.hasNext() ) {
            AppData curr = appItr.next();
            if (prior != null) {
                if ( curr.getGroup() == prior.getGroup() ) {
                    prior.merge(curr);
                    appItr.remove();
                }
            }
        }
        
        return data;
    }

    private String generateListWitContent(Set<Witness> witnesses) throws IOException {
        StringBuilder listWit = new StringBuilder();
        for (Witness w : witnesses ) {
            if ( listWit.length() > 0 ) {
                listWit.append("\n                    ");
            }
            String frag = IOUtils.toString(ClassLoader.getSystemResourceAsStream("templates/xml/listwit_frag.xml"));
            frag = frag.replace("$NAME", w.getName());
            frag = frag.replace("$ID", "wit-"+w.getId().toString());
            listWit.append(frag);
        }
        return listWit.toString();
    }
    
    private static class AppData {
        private Long baseId;
        private int groupId;
        private Range baseRange;
        private Map<Long, Range> witnessRanges = new HashMap<Long, Range>();
        
        public AppData( Long baseId, Range r, int groupId) {
            this.baseId = baseId;
            this.baseRange = new Range(r);
            this.groupId = groupId;
        }
        public void addWitness( Long id, Range r) {
            this.witnessRanges.put(id, new Range(r));
        }
        public Map<Long, Range> getWitnessData() {
            return this.witnessRanges;
        }
        public int getGroup() {
            return this.groupId;
        }
        public void merge(AppData other) {
            // TODO
        }
        public Long getBaseId() {
            return this.baseId;
        }
        public Range getBaseRange() {
            return this.baseRange;
        }
    }
}
