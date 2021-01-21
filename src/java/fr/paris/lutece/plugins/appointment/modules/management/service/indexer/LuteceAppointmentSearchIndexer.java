package fr.paris.lutece.plugins.appointment.modules.management.service.indexer;

import java.io.IOException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.apache.commons.collections.CollectionUtils;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.BytesRef;
import org.springframework.beans.factory.annotation.Autowired;

import fr.paris.lutece.plugins.appointment.business.appointment.Appointment;
import fr.paris.lutece.plugins.appointment.business.appointment.AppointmentHome;
import fr.paris.lutece.plugins.appointment.business.form.Form;
import fr.paris.lutece.plugins.appointment.business.form.FormHome;
import fr.paris.lutece.plugins.appointment.business.user.User;
import fr.paris.lutece.plugins.appointment.business.user.UserHome;
import fr.paris.lutece.plugins.appointment.modules.management.business.search.AppointmentSearchItem;
import fr.paris.lutece.plugins.appointment.service.AppointmentService;
import fr.paris.lutece.plugins.appointment.web.dto.AppointmentDTO;
import fr.paris.lutece.plugins.appointment.web.dto.AppointmentFilterDTO;
import fr.paris.lutece.plugins.workflowcore.business.state.State;
import fr.paris.lutece.plugins.workflowcore.service.state.StateService;
import fr.paris.lutece.portal.business.indexeraction.IndexerAction;
import fr.paris.lutece.portal.business.indexeraction.IndexerActionFilter;
import fr.paris.lutece.portal.business.indexeraction.IndexerActionHome;
import fr.paris.lutece.portal.service.i18n.I18nService;
import fr.paris.lutece.portal.service.message.SiteMessageException;
import fr.paris.lutece.portal.service.search.IndexationService;
import fr.paris.lutece.portal.service.search.SearchItem;
import fr.paris.lutece.portal.service.util.AppLogService;
import fr.paris.lutece.portal.service.util.AppPropertiesService;

/**
 * Appointment global indexer
 */
public class LuteceAppointmentSearchIndexer implements IAppointmentSearchIndexer
{

    private static final String APPOINTMENTS = "appointments";
    private static final String INDEXER_NAME = "AppointmentIndexer";
    private static final String INDEXER_DESCRIPTION = "Indexer service for appointment";
    private static final String INDEXER_VERSION = "1.0.0";
    private static final String PROPERTY_INDEXER_ENABLE = "appointment-management.globalIndexer.enable";
    private static final int TAILLE_LOT = AppPropertiesService.getPropertyInt( "appointment-management.index.writer.commit.size", 100 );

    @Inject
    private LuceneAppointmentIndexFactory _luceneAppointmentIndexFactory;
    private IndexWriter _indexWriter;
    
    @Autowired( required = false )
    private StateService _stateService;
    
    private static AtomicBoolean _bIndexIsRunning = new AtomicBoolean( false );
    private static AtomicBoolean _bIndexToLunch = new AtomicBoolean( false );
    
    private static final Object LOCK = new Object( );
    
    public LuteceAppointmentSearchIndexer( )
    {
        IndexationService.registerIndexer( this );
    }
    
    @Override
    public String getName( )
    {
        return INDEXER_NAME;
    }

    @Override
    public String getDescription( )
    {
        return INDEXER_DESCRIPTION;
    }
    
    @Override
    public String getVersion( )
    {
        return INDEXER_VERSION;
    }
    
    @Override
    public boolean isEnable( )
    {
        return AppPropertiesService.getPropertyBoolean( PROPERTY_INDEXER_ENABLE, false );
    }
    
    @Override
    public List<String> getListType( )
    {
        return Collections.singletonList( APPOINTMENTS );
    }
    
    @Override
    public String getSpecificSearchAppUrl( )
    {
        return "";
    }
    
    @Override
    public void indexDocuments( ) throws IOException, InterruptedException, SiteMessageException
    {
        List<Integer> listAppointmentId = AppointmentHome.selectAllAppointmentId( );
        
        deleteIndex( );
        _bIndexToLunch.set( true );
        
        if ( _bIndexIsRunning.compareAndSet( false, true ) )
        {
            new Thread( new IndexerRunnable( listAppointmentId ) ).start( );
        }
    }
    
    @Override
    public void indexDocument( int nIdAppointment, int idTask )
    {
        IndexerAction action = new IndexerAction( );
        action.setIdDocument( String.valueOf( nIdAppointment ) );
        action.setIdTask( idTask );
        action.setIndexerName( INDEXER_NAME );
        IndexerActionHome.create( action );
        
        _bIndexToLunch.set( true );
        if ( _bIndexIsRunning.compareAndSet( false, true ) )
        {
            new Thread( new IndexerRunnable( ) ).start( );
        }
    }
    
    @Override
    public List<Document> getDocuments( String strIdDocument ) throws IOException, InterruptedException, SiteMessageException
    {
        int nIdAppointment;

        try
        {
            nIdAppointment = Integer.parseInt( strIdDocument );
        }
        catch( NumberFormatException ne )
        {
            AppLogService.error( strIdDocument + " not parseable to an int", ne );
            return new ArrayList<>( 0 );
        }
        
        AppointmentDTO appointment = AppointmentService.buildAppointmentDTOFromIdAppointment( nIdAppointment );
        Form form = FormHome.findByPrimaryKey( appointment.getIdForm( ) );
        
        State appointmentState = null;
        if ( _stateService != null )
        {
            appointmentState = _stateService.findByResource( appointment.getIdAppointment( ), Appointment.APPOINTMENT_RESOURCE_TYPE, form.getIdWorkflow( ) );
        }
        
        Document doc = getDocument( appointment, appointmentState );
        
        List<Document> listDocument = new ArrayList<>( 1 );
        listDocument.add( doc );
        return listDocument;
    }
    
    /**
     * Builds a document which will be used by Lucene during the indexing of this record
     * 
     * @param appointmentDTO
     *            the appointment object
     * @param form
     *            the form
     * @return a lucene document filled with the record data
     */
    private Document getDocument( AppointmentDTO appointmentDTO, State appointmentState )
    {
        // make a new, empty document
        Document doc = new Document( );
        
        int nIdAppointment = appointmentDTO.getIdAppointment( );

        // --- document identifier
        doc.add( new StringField( SearchItem.FIELD_UID, String.valueOf( nIdAppointment ), Field.Store.YES ) );
        
        // --- form response identifier
        doc.add( new IntPoint( AppointmentSearchItem.FIELD_ID_APPOINTMENT, nIdAppointment ) );
        doc.add( new NumericDocValuesField( AppointmentSearchItem.FIELD_ID_APPOINTMENT, nIdAppointment ) );
        doc.add( new StoredField( AppointmentSearchItem.FIELD_ID_APPOINTMENT, nIdAppointment ) );
        
        // --- id form
        doc.add( new IntPoint( AppointmentSearchItem.FIELD_ID_FORM, appointmentDTO.getIdForm( ) ) );
        doc.add( new NumericDocValuesField( AppointmentSearchItem.FIELD_ID_FORM, appointmentDTO.getIdForm( ) ) );
        doc.add( new StoredField( AppointmentSearchItem.FIELD_ID_FORM, appointmentDTO.getIdForm( ) ) );
        
        // --- First name
        doc.add( new StringField( AppointmentSearchItem.FIELD_FIRST_NAME, appointmentDTO.getFirstName( ), Field.Store.YES ) );
        doc.add( new SortedDocValuesField( AppointmentSearchItem.FIELD_FIRST_NAME, new BytesRef( appointmentDTO.getFirstName( ) ) ) );
        
        // --- First name
        doc.add( new StringField( AppointmentSearchItem.FIELD_LAST_NAME, appointmentDTO.getLastName( ), Field.Store.YES ) );
        doc.add( new SortedDocValuesField( AppointmentSearchItem.FIELD_LAST_NAME, new BytesRef( appointmentDTO.getLastName( ) ) ) );
        
        // --- Mail
        doc.add( new StringField( AppointmentSearchItem.FIELD_MAIL, appointmentDTO.getEmail( ), Field.Store.YES ) );
        doc.add( new SortedDocValuesField( AppointmentSearchItem.FIELD_MAIL, new BytesRef( appointmentDTO.getEmail( ) ) ) );
        
        // --- Starting date appointment
        Long longStartDate = Timestamp.valueOf( appointmentDTO.getStartingDateTime( ) ).getTime( );
        doc.add( new LongPoint( AppointmentSearchItem.FIELD_START_DATE, longStartDate ) );
        doc.add( new NumericDocValuesField( AppointmentSearchItem.FIELD_START_DATE, longStartDate ) );
        doc.add( new StoredField( AppointmentSearchItem.FIELD_START_DATE, longStartDate ) );
        
        // --- Ending date appointment
        Long longEndDate = Timestamp.valueOf( appointmentDTO.getEndingDateTime( ) ).getTime( );
        doc.add( new LongPoint( AppointmentSearchItem.FIELD_END_DATE, longEndDate ) );
        doc.add( new NumericDocValuesField( AppointmentSearchItem.FIELD_END_DATE, longEndDate ) );
        doc.add( new StoredField( AppointmentSearchItem.FIELD_END_DATE, longEndDate ) );
        
        // --- Admin user
        String admin = appointmentDTO.getAdminUser( );
        doc.add( new StringField( AppointmentSearchItem.FIELD_ADMIN, admin, Field.Store.YES ) );
        doc.add( new SortedDocValuesField( AppointmentSearchItem.FIELD_ADMIN, new BytesRef( admin ) ) );
        
        // --- Status
        String status = I18nService.getLocalizedString( AppointmentDTO.PROPERTY_APPOINTMENT_STATUS_RESERVED, Locale.getDefault( ) );
        if ( appointmentDTO.getIsCancelled( ) )
        {
            status = I18nService.getLocalizedString( AppointmentDTO.PROPERTY_APPOINTMENT_STATUS_UNRESERVED, Locale.getDefault( ) );
        }
        doc.add( new StringField( AppointmentSearchItem.FIELD_STATUS, status, Field.Store.YES ) );
        doc.add( new SortedDocValuesField( AppointmentSearchItem.FIELD_STATUS, new BytesRef( status ) ) );
        
        // --- State
        if ( appointmentState != null )
        {
            // --- id form response workflow state
            int nIdWorkflowState = appointmentState.getId( );
            doc.add( new IntPoint( AppointmentSearchItem.FIELD_ID_WORKFLOW_STATE, nIdWorkflowState ) );
            doc.add( new NumericDocValuesField( AppointmentSearchItem.FIELD_ID_WORKFLOW_STATE, nIdWorkflowState ) );
            doc.add( new StoredField( AppointmentSearchItem.FIELD_ID_WORKFLOW_STATE, nIdWorkflowState ) );
        }
        
        // --- Nb Seats
        doc.add( new IntPoint( AppointmentSearchItem.FIELD_NB_SEATS, appointmentDTO.getNbBookedSeats( ) ) );
        doc.add( new NumericDocValuesField( AppointmentSearchItem.FIELD_NB_SEATS, appointmentDTO.getNbBookedSeats( ) ) );
        doc.add( new StoredField( AppointmentSearchItem.FIELD_NB_SEATS, appointmentDTO.getNbBookedSeats( ) ) );
        
        // --- Date appointment Taken
        Long longAppointmentTaken = Timestamp.valueOf( appointmentDTO.getDateAppointmentTaken( ) ).getTime( );
        doc.add( new LongPoint( AppointmentSearchItem.FIELD_DATE_APPOINTMENT_TAKEN, longAppointmentTaken ) );
        doc.add( new NumericDocValuesField( AppointmentSearchItem.FIELD_DATE_APPOINTMENT_TAKEN, longAppointmentTaken ) );
        doc.add( new StoredField( AppointmentSearchItem.FIELD_DATE_APPOINTMENT_TAKEN, longAppointmentTaken ) );
        
        return doc;
    }
    
    private void deleteIndex( )
    {
        if ( _indexWriter == null || !_indexWriter.isOpen( ) )
        {
            initIndexing( true );
        }
        try
        {
            _indexWriter.deleteAll( );
        }
        catch( IOException e )
        {
            AppLogService.error( "Unable to delete all docs in index ", e );
        }
        finally
        {
            endIndexing( );
        }
    }
    
    /**
     * Init the indexing action
     * 
     * @param bCreate
     */
    private void initIndexing( boolean bCreate )
    {
        _indexWriter = _luceneAppointmentIndexFactory.getIndexWriter( bCreate );
    }
    
    /**
     * End the indexing action
     */
    private void endIndexing( )
    {
        if ( _indexWriter != null )
        {
            try
            {
                _indexWriter.commit( );
            }
            catch( IOException e )
            {
                AppLogService.error( "Unable to close index writer ", e );
            }
        }
    }
    
    private class IndexerRunnable implements Runnable
    {
        private final List<Integer> _idList;
        
        public IndexerRunnable( List<Integer> idList )
        {
            _idList = idList;
        }
        
        public IndexerRunnable( )
        {
            _idList = new ArrayList<>( );
        }
        
        @Override
        public void run( )
        {
            try
            {
                processIdList( _idList );
                while ( _bIndexToLunch.compareAndSet( true, false ) )
                {
                    processIndexing( );
                }
            }
            catch( Exception e )
            {
                AppLogService.error( e.getMessage( ), e );
                Thread.currentThread( ).interrupt( );
            }
            finally
            {
                _bIndexIsRunning.set( false );
            }
        }
        
        private void processIndexing( )
        {
            synchronized( LOCK )
            {
                initIndexing( false );
                
                Set<Integer> listIdsToAdd = new HashSet<>( );
                Set<Integer> listIdsToDelete = new HashSet<>( );
                
                // Delete all record which must be delete
                IndexerActionFilter filter = new IndexerActionFilter( );
                filter.setIdTask( IndexerAction.TASK_DELETE );
                for ( IndexerAction action : IndexerActionHome.getList( filter ) )
                {
                    listIdsToDelete.add( Integer.valueOf( action.getIdDocument( ) ) );
                    IndexerActionHome.remove( action.getIdAction( ) );
                }
                
                // Update all record which must be update
                filter.setIdTask( IndexerAction.TASK_MODIFY );
                for ( IndexerAction action : IndexerActionHome.getList( filter ) )
                {
                    listIdsToDelete.add( Integer.valueOf( action.getIdDocument( ) ) );
                    listIdsToAdd.add( Integer.valueOf( action.getIdDocument( ) ) );
                    IndexerActionHome.remove( action.getIdAction( ) );
                }
                
                // Update all record which must be update
                filter.setIdTask( IndexerAction.TASK_CREATE );
                for ( IndexerAction action : IndexerActionHome.getList( filter ) )
                {
                    listIdsToAdd.add( Integer.valueOf( action.getIdDocument( ) ) );
                    IndexerActionHome.remove( action.getIdAction( ) );
                }
                
                List<Query> queryList = new ArrayList<>( TAILLE_LOT );
                for ( Integer nIdAppointment : listIdsToDelete )
                {
                    queryList.add( IntPoint.newExactQuery( AppointmentSearchItem.FIELD_ID_APPOINTMENT, nIdAppointment ) );
                    if ( queryList.size( ) == TAILLE_LOT )
                    {
                        deleteDocument( queryList );
                        queryList.clear( );
                    }
                }
                deleteDocument( queryList );
                queryList.clear( );
                
                processIdList( listIdsToAdd );
                
                endIndexing( );
            }
        }
        
        private void processIdList( Collection<Integer> idList )
        {
            List<Integer> partialIdList = new ArrayList<>( TAILLE_LOT );
            for ( Integer nIdAppointment : idList )
            {
                partialIdList.add( nIdAppointment );
                if ( partialIdList.size( ) == TAILLE_LOT )
                {
                    AppointmentFilterDTO filter = new AppointmentFilterDTO( );
                    filter.setListIdAppointment( partialIdList );
                    
                    List<AppointmentDTO> appointmentList = AppointmentService.findListAppointmentsDTOByFilter( filter );
                    indexAppointmentList( appointmentList );
                    partialIdList.clear( );
                    appointmentList.clear( );
                }
            }
            if ( CollectionUtils.isNotEmpty( partialIdList ) )
            {
                AppointmentFilterDTO filter = new AppointmentFilterDTO( );
                filter.setListIdAppointment( partialIdList );
                
                List<AppointmentDTO> appointmentList = AppointmentService.findListAppointmentsDTOByFilter( filter );
                indexAppointmentList( appointmentList );
                partialIdList.clear( );
                appointmentList.clear( );
            }
        }
        
        /**
         * {@inheritDoc}
         */
        private void indexAppointmentList( List<AppointmentDTO> listAppointment )
        {
            if ( _indexWriter == null || !_indexWriter.isOpen( ) )
            {
                initIndexing( true );
            }
            
            Map<Integer, Form> mapForms = FormHome.findAllForms( ).stream( ).collect( Collectors.toMap( Form::getIdForm, Function.identity( ) ) );
            List<Document> documentList = new ArrayList<>( );
            
            for ( AppointmentDTO appointment : listAppointment )
            {
                if ( appointment.getIdUser( ) > 0 )
                {
                    User user = UserHome.findByPrimaryKey( appointment.getIdUser( ) );
                    appointment.setUser( user );
                }
                
                int formId = appointment.getSlot( ).get( 0 ).getIdForm( );
                Form form = mapForms.get( formId );
                
                State appointmentState = null;
                if ( _stateService != null )
                {
                    appointmentState = _stateService.findByResource( appointment.getIdAppointment( ), Appointment.APPOINTMENT_RESOURCE_TYPE, form.getIdWorkflow( ) );
                }
                Document doc = null;
                try
                {
                    doc = getDocument( appointment, appointmentState );
                }
                catch( Exception e )
                {
                    AppLogService.error( "Unable to index appointment with id " + appointment.getIdAppointment( ), e );
                }

                if ( doc != null )
                {
                    documentList.add( doc );
                }
            }
            addDocuments( documentList );
            endIndexing( );
        }
        
        private void addDocuments( List<Document> documentList )
        {
            try
            {
                _indexWriter.addDocuments( documentList );
            }
            catch( IOException e )
            {
                AppLogService.error( "Unable to index documents", e );
            }
            documentList.clear( );
        }
        
        private void deleteDocument( List<Query> luceneQueryList )
        {
            try
            {
                _indexWriter.deleteDocuments( luceneQueryList.toArray( new Query [ luceneQueryList.size( )] ) );
            }
            catch( IOException e )
            {
                AppLogService.error( "Unable to delete document ", e );
            }
        }
    }
}
