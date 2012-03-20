package org.juxtasoftware.resource;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.juxtasoftware.dao.ComparisonSetDao;
import org.juxtasoftware.dao.SourceDao;
import org.juxtasoftware.dao.TemplateDao;
import org.juxtasoftware.dao.WitnessDao;
import org.juxtasoftware.model.ComparisonSet;
import org.juxtasoftware.model.Source;
import org.juxtasoftware.model.Template;
import org.juxtasoftware.model.Witness;
import org.juxtasoftware.service.importer.ImportService;
import org.juxtasoftware.service.importer.jxt.JxtImportServiceImpl;
import org.juxtasoftware.service.importer.ps.ParallelSegmentationImportImpl;
import org.juxtasoftware.util.BackgroundTask;
import org.juxtasoftware.util.BackgroundTaskCanceledException;
import org.juxtasoftware.util.BackgroundTaskStatus;
import org.juxtasoftware.util.TaskManager;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.ext.fileupload.RestletFileUpload;
import org.restlet.representation.Representation;
import org.restlet.resource.Get;
import org.restlet.resource.Post;
import org.restlet.resource.ResourceException;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Resource used to import JXT files from desktop to juxta-ws. It also
 * supports import of TEI parallel segmentation files. 
 *  
 * @author lfoster
 *
 */
@Service
@Scope(BeanDefinition.SCOPE_PROTOTYPE)
public class Importer extends BaseResource implements ApplicationContextAware {
    private enum Action { IMPORT, STATUS, CANCEL }
    
    private Action action;
    private Long setId = null;
    private boolean overwrite = false;
    private ApplicationContext context;
    @Autowired private TaskManager taskManager;
    @Autowired private ComparisonSetDao setDao;
    @Autowired private WitnessDao witnessDao;
    @Autowired private SourceDao sourceDao;
    @Autowired private TemplateDao templateDao;    

    @Override
    public void setApplicationContext(ApplicationContext context) throws BeansException {
        this.context = context;
    }
    
    @Override
    protected void doInit() throws ResourceException {
        String act  = getRequest().getResourceRef().getLastSegment().toUpperCase();
        this.action = Action.valueOf(act);
        if ( this.action == null ) {
            setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
        }
        
        // was the overwrite flag specified?
        if ( getQuery().getValuesMap().containsKey("overwrite"))   {
            this.overwrite = true;
        }
        
        // grab import id for status checks and cancels
        if ( getRequest().getAttributes().containsKey("id"))   {
            this.setId = Long.parseLong( (String)getRequest().getAttributes().get("id"));
        }

        super.doInit();
    }
    
    /**
     * Accepts a JXT file upload and imports it into the juxta WS data model
     * 
     * @param entity
     * @return
     */
    @Post
    public Representation acceptPost(Representation entity ) {
        if ( this.action.equals(Action.IMPORT)) {
            return doImport( entity );
        } else if ( this.action.equals(Action.CANCEL)) {
            cancelImport();
            return toTextRepresentation("");
        } else {
            setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
            return toTextRepresentation("Invalid action requested");
        } 
    }
    
    @Get("json")
    public Representation handeGet() {
        if ( this.action.equals(Action.STATUS)) {
            return getImportStatus();
        }
        setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
        return toTextRepresentation("Invalid action requested");
    }
    
    private Representation getImportStatus() {
        String json = this.taskManager.getStatus( generateTaskName(this.setId) );
        return toJsonRepresentation(json);
    }

    private void cancelImport() {
        LOG.info("Cancel import " + this.setId);
        this.taskManager.cancel( generateTaskName(this.setId) );
    }
    
    private Representation doImport( Representation entity ) {
    
        if ( entity == null ) {
            setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
            return null;
        }
        
        if (MediaType.MULTIPART_FORM_DATA.equals(entity.getMediaType(),true)) {
            return handleMutipartPost( entity );
        } else if ( MediaType.APPLICATION_JSON.equals(entity.getMediaType(),true)) {
            return handleJsonPost( entity );
        }
        
        setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
        return toTextRepresentation("Invalid post data format");
    }
    
    private Representation handleMutipartPost(Representation entity) {
        // data for creation of new comparison set
        String setName = null;
        InputStream is = null;
        
        // Parse the multipart request....
        try {
            // pull the list of items in this multipart request
            DiskFileItemFactory factory = new DiskFileItemFactory();
            factory.setSizeThreshold(1000240);
            RestletFileUpload upload = new RestletFileUpload(factory);
            List<FileItem> items = upload.parseRequest(getRequest());
            for ( FileItem item : items ) {
                if ( item.getFieldName().equals("setName")) {
                    setName = item.getString();
                } else if ( item.getFieldName().equals("jxtFile")) {
                    is = item.getInputStream();
                } 
            }
            
            // validate that everything needed is present
            if ( setName == null || is == null ) {
                setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
                return toTextRepresentation("Missing required JXT parameters");
            }
        } catch (Exception e) {
            LOG.error("Unable to parse multipart data", e);
            setStatus(Status.CLIENT_ERROR_BAD_REQUEST );
            return toTextRepresentation("File upload failed");
        }
        

        try {
            // create set to contain the data
            ComparisonSet set = createImportSet(setName);
            
            // dump data into new set
            ImportService<InputStream> importService = this.context.getBean(JxtImportServiceImpl.class);
            this.taskManager.submit( new ImportTask<InputStream>(importService, set, is));
            
            // return set id so its import status can be tracked
            return toTextRepresentation( set.getId().toString() );
        } catch (IOException e) {
            if ( getStatus() == Status.SUCCESS_OK ) {
                setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
            }
            return toTextRepresentation( "Unable to import data: "+e.toString() );
        } 
    }

    private Representation handleJsonPost(Representation entity) {
        
        try {
            JsonParser parser = new JsonParser();
            JsonObject jsonObj = parser.parse( entity.getText()).getAsJsonObject();
            String setName = jsonObj.get("setName").getAsString();
            Long srcId = jsonObj.get("teiSourceId").getAsLong();
            
            // create source and set
            Source src = this.sourceDao.find(this.workspace.getId(), srcId);
            ComparisonSet set = createImportSet(setName);
            
            // dump data into new set
            ImportService<Source> importService = this.context.getBean(ParallelSegmentationImportImpl.class);
            this.taskManager.submit( new ImportTask<Source>(importService, set, src));
            
            // return set id so its import status can be tracked
            return toTextRepresentation( set.getId().toString() );
        } catch (Exception e) {
            
            if ( getStatus() == Status.SUCCESS_OK ) {
                setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
            }
            return toTextRepresentation( "Unable to import data: "+e.toString() );
        }
    }
    
    private ComparisonSet createImportSet( final String setName) throws IOException {
        ComparisonSet set = this.setDao.find(this.workspace, setName);
        if ( set != null ) {
            if ( this.overwrite == false ) {
                setStatus(Status.CLIENT_ERROR_CONFLICT, "Conflict with existing comparison set name");
                throw new IOException("Conflict with existing comparison set name");
            }
            return set;
        } else {
            set = new ComparisonSet();
            set.setName(setName);
            set.setWorkspaceId( this.workspace.getId() );
            Long id  = this.setDao.create(set);
            set.setId(id);
            return set;
        }
    }
    
    private static String generateTaskName(long setId ) {
        return "import-"+setId;
    }
    
    private void cleanupCanceledImport( ComparisonSet set ) {
        // first, grab the set info so we can have a list of all witnesses
        // and their associated metadata.
        Set<Witness> witnesses = this.setDao.getWitnesses(set);
        
        // kill the set first
        this.setDao.delete(set);
        
        // kill the witnesses and associated sources
        List<Long> templateIdList = new ArrayList<Long>();
        for ( Witness witness :witnesses ) {
            Long sourceId  = witness.getSourceId();
            templateIdList.add( witness.getTemplateId() );
            
            // Next, kill the witness
            this.witnessDao.delete(witness);
            
            // Next, kill the orphaned source
            Source s = this.sourceDao.find(this.workspace.getId(), sourceId);
            this.sourceDao.delete(s);
        }
        
        // Finally, iterate over all templates and kill them too
        for (Long templateId : templateIdList ) {
            Template t = this.templateDao.find(templateId);
            this.templateDao.delete(t);
        }
    }
    
    /**
     * Task to asynchronously execute the import
     */
    private class ImportTask<T> implements BackgroundTask {
        private BackgroundTaskStatus task;
        private T importSouce;
        private ComparisonSet set;
        private Date startDate;
        private Date endDate;
        private String taskName;
        private ImportService<T> importer;
        private BackgroundTaskStatus.Status status = BackgroundTaskStatus.Status.PENDING;
        
        public ImportTask(ImportService<T> importService, ComparisonSet set, T importSouce) {
            this.set = set;
            this.importSouce = importSouce;
            this.taskName  = generateTaskName( set.getId() );
            this.task = new BackgroundTaskStatus( this.taskName );
            this.startDate = new Date();
            this.importer = importService;
        }
        
        @Override
        public void run() {
            try {
                LOG.info("Begin task "+this.taskName);
                this.status = BackgroundTaskStatus.Status.PROCESSING;
                this.task.begin();
                this.importer.doImport(this.set, this.importSouce, this.task);
                LOG.info("task "+this.taskName+" COMPLETE");
                this.endDate = new Date();
                this.status = BackgroundTaskStatus.Status.COMPLETE;
            } catch ( BackgroundTaskCanceledException e) {
                LOG.info( this.taskName+" task was canceled");
                cleanupCanceledImport( this.set );
                this.endDate = new Date();
                this.status = BackgroundTaskStatus.Status.CANCELED;
            } catch (Exception e) {
                LOG.error(this.taskName+" task failed", e);
                this.task.fail(e.getMessage());
                this.endDate = new Date();
                this.status = BackgroundTaskStatus.Status.FAILED;
            }
        }
        
        @Override
        public void cancel() {
            this.task.cancel();
        }

        @Override
        public BackgroundTaskStatus.Status getStatus() {
            return this.status;
        }

        @Override
        public String getName() {
            return this.taskName;
        }
        
        @Override
        public Date getEndTime() {
            return this.endDate;
        }
        
        @Override
        public Date getStartTime() {
            return this.startDate;
        }
        
        @Override
        public String getMessage() {
            return this.task.getNote();
        }
    }
}
