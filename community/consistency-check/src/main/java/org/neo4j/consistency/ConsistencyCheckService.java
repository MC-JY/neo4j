/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.consistency;

import java.io.IOException;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;

import org.neo4j.configuration.Config;
import org.neo4j.configuration.GraphDatabaseInternalSettings;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.configuration.helpers.DatabaseReadOnlyChecker;
import org.neo4j.consistency.checker.DebugContext;
import org.neo4j.consistency.checker.NodeBasedMemoryLimiter;
import org.neo4j.consistency.checking.full.ConsistencyCheckIncompleteException;
import org.neo4j.consistency.checking.full.ConsistencyFlags;
import org.neo4j.consistency.checking.full.FullCheck;
import org.neo4j.consistency.report.ConsistencySummaryStatistics;
import org.neo4j.consistency.store.DirectStoreAccess;
import org.neo4j.counts.CountsAccessor;
import org.neo4j.counts.CountsStorage;
import org.neo4j.counts.CountsStore;
import org.neo4j.function.ThrowingSupplier;
import org.neo4j.index.internal.gbptree.RecoveryCleanupWorkCollector;
import org.neo4j.internal.counts.CountsBuilder;
import org.neo4j.internal.counts.GBPTreeCountsStore;
import org.neo4j.internal.counts.GBPTreeRelationshipGroupDegreesStore;
import org.neo4j.internal.counts.RelationshipGroupDegreesStore;
import org.neo4j.internal.helpers.progress.ProgressMonitorFactory;
import org.neo4j.internal.id.DefaultIdGeneratorFactory;
import org.neo4j.internal.recordstorage.StoreTokens;
import org.neo4j.io.fs.DefaultFileSystemAbstraction;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.layout.DatabaseLayout;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.io.pagecache.context.CursorContext;
import org.neo4j.io.pagecache.tracing.PageCacheTracer;
import org.neo4j.kernel.extension.DatabaseExtensions;
import org.neo4j.kernel.impl.api.index.stats.IndexStatisticsStore;
import org.neo4j.kernel.impl.pagecache.ConfiguringPageCacheFactory;
import org.neo4j.kernel.impl.scheduler.JobSchedulerFactory;
import org.neo4j.kernel.impl.store.NeoStores;
import org.neo4j.kernel.impl.store.StoreFactory;
import org.neo4j.kernel.impl.transaction.state.DefaultIndexProviderMap;
import org.neo4j.kernel.lifecycle.LifeSupport;
import org.neo4j.kernel.lifecycle.LifecycleAdapter;
import org.neo4j.logging.DuplicatingLog;
import org.neo4j.logging.Level;
import org.neo4j.logging.Log;
import org.neo4j.logging.LogProvider;
import org.neo4j.logging.internal.SimpleLogService;
import org.neo4j.logging.log4j.Log4jLogProvider;
import org.neo4j.logging.log4j.LogConfig;
import org.neo4j.memory.EmptyMemoryTracker;
import org.neo4j.memory.MemoryPools;
import org.neo4j.memory.MemoryTracker;
import org.neo4j.monitoring.Monitors;
import org.neo4j.scheduler.JobScheduler;
import org.neo4j.time.Clocks;
import org.neo4j.token.DelegatingTokenHolder;
import org.neo4j.token.ReadOnlyTokenCreator;
import org.neo4j.token.TokenHolders;
import org.neo4j.token.api.TokenHolder;

import static java.lang.String.format;
import static org.neo4j.configuration.GraphDatabaseSettings.memory_tracking;
import static org.neo4j.configuration.helpers.DatabaseReadOnlyChecker.readOnly;
import static org.neo4j.consistency.checking.full.ConsistencyFlags.DEFAULT;
import static org.neo4j.consistency.internal.SchemaIndexExtensionLoader.instantiateExtensions;
import static org.neo4j.index.internal.gbptree.RecoveryCleanupWorkCollector.immediate;
import static org.neo4j.internal.helpers.Strings.joinAsLines;
import static org.neo4j.io.pagecache.context.CursorContext.NULL;
import static org.neo4j.kernel.impl.factory.DbmsInfo.TOOL;
import static org.neo4j.kernel.recovery.Recovery.isRecoveryRequired;

public class ConsistencyCheckService
{
    private static final String CONSISTENCY_TOKEN_READER_TAG = "consistencyTokenReader";
    private final Date timestamp;

    public ConsistencyCheckService()
    {
        this( new Date() );
    }

    public ConsistencyCheckService( Date timestamp )
    {
        this.timestamp = timestamp;
    }

    @Deprecated
    public Result runFullConsistencyCheck( DatabaseLayout databaseLayout, Config tuningConfiguration,
            ProgressMonitorFactory progressFactory, LogProvider logProvider, boolean verbose )
            throws ConsistencyCheckIncompleteException
    {
        return runFullConsistencyCheck( databaseLayout, tuningConfiguration, progressFactory, logProvider, verbose,
                DEFAULT );
    }

    public Result runFullConsistencyCheck( DatabaseLayout databaseLayout, Config config, ProgressMonitorFactory progressFactory,
            LogProvider logProvider, boolean verbose, ConsistencyFlags consistencyFlags )
            throws ConsistencyCheckIncompleteException
    {
        FileSystemAbstraction fileSystem = new DefaultFileSystemAbstraction();
        try
        {
            return runFullConsistencyCheck( databaseLayout, config, progressFactory, logProvider, fileSystem, verbose, consistencyFlags );
        }
        finally
        {
            try
            {
                fileSystem.close();
            }
            catch ( IOException e )
            {
                Log log = logProvider.getLog( getClass() );
                log.error( "Failure during shutdown of file system", e );
            }
        }
    }

    public Result runFullConsistencyCheck( DatabaseLayout databaseLayout, Config config,
            ProgressMonitorFactory progressFactory, LogProvider logProvider, FileSystemAbstraction fileSystem, boolean verbose,
            ConsistencyFlags consistencyFlags ) throws ConsistencyCheckIncompleteException
    {
        return runFullConsistencyCheck( databaseLayout, config, progressFactory, logProvider, fileSystem, verbose,
                defaultReportDir( config ), consistencyFlags );
    }

    public Result runFullConsistencyCheck( DatabaseLayout databaseLayout, Config config,
            ProgressMonitorFactory progressFactory, LogProvider logProvider, FileSystemAbstraction fileSystem, boolean verbose, Path reportDir,
            ConsistencyFlags consistencyFlags ) throws ConsistencyCheckIncompleteException
    {
        Log log = logProvider.getLog( getClass() );
        JobScheduler jobScheduler = JobSchedulerFactory.createInitialisedScheduler();
        var pageCacheTracer = PageCacheTracer.NULL;
        var memoryTracker = EmptyMemoryTracker.INSTANCE;
        ConfiguringPageCacheFactory pageCacheFactory =
                new ConfiguringPageCacheFactory( fileSystem, config, pageCacheTracer, logProvider.getLog( PageCache.class ),
                        jobScheduler, Clocks.nanoClock(), new MemoryPools( config.get( memory_tracking ) ) );
        PageCache pageCache = pageCacheFactory.getOrCreatePageCache();

        try
        {
            return runFullConsistencyCheck( databaseLayout, config, progressFactory, logProvider, fileSystem, pageCache, verbose,
                    reportDir, consistencyFlags, pageCacheTracer, memoryTracker );
        }
        finally
        {
            try
            {
                pageCache.close();
            }
            catch ( Exception e )
            {
                log.error( "Failure during shutdown of the page cache", e );
            }
            try
            {
                jobScheduler.close();
            }
            catch ( Exception e )
            {
                log.error( "Failure during shutdown of the job scheduler", e );
            }
        }
    }

    public Result runFullConsistencyCheck( DatabaseLayout databaseLayout, Config config, ProgressMonitorFactory progressFactory, LogProvider logProvider,
            FileSystemAbstraction fileSystem, PageCache pageCache, boolean verbose, ConsistencyFlags consistencyFlags, PageCacheTracer pageCacheTracer,
            MemoryTracker memoryTracker ) throws ConsistencyCheckIncompleteException
    {
        return runFullConsistencyCheck( databaseLayout, config, progressFactory, logProvider, fileSystem, pageCache, verbose,
                defaultReportDir( config ), consistencyFlags, pageCacheTracer, memoryTracker );
    }

    public Result runFullConsistencyCheck( DatabaseLayout databaseLayout, Config config,
            ProgressMonitorFactory progressFactory, final LogProvider logProvider, final FileSystemAbstraction fileSystem, final PageCache pageCache,
            boolean verbose, Path reportDir, ConsistencyFlags consistencyFlags, PageCacheTracer pageCacheTracer, MemoryTracker memoryTracker )
            throws ConsistencyCheckIncompleteException
    {
        DebugContext debugContext = new DebugContext()
        {
            @Override
            public boolean debugEnabled()
            {
                return verbose;
            }

            @Override
            public void debug( String message )
            {
                System.out.println( message );
            }
        };
        return runFullConsistencyCheck( databaseLayout, config, progressFactory, logProvider, fileSystem, pageCache, debugContext, reportDir,
                consistencyFlags, pageCacheTracer, memoryTracker );
    }

    public Result runFullConsistencyCheck( DatabaseLayout databaseLayout, Config config,
            ProgressMonitorFactory progressFactory, final LogProvider logProvider, final FileSystemAbstraction fileSystem, final PageCache pageCache,
            DebugContext debugContext, Path reportDir, ConsistencyFlags consistencyFlags, PageCacheTracer pageCacheTracer, MemoryTracker memoryTracker )
            throws ConsistencyCheckIncompleteException
    {
        assertRecovered( databaseLayout, config, fileSystem, memoryTracker );
        Log outLog = logProvider.getLog( getClass() );
        config.set( GraphDatabaseSettings.pagecache_warmup_enabled, false );

        LifeSupport life = new LifeSupport();
        final DefaultIdGeneratorFactory idGeneratorFactory = new DefaultIdGeneratorFactory( fileSystem, immediate(), databaseLayout.getDatabaseName() );
        DatabaseReadOnlyChecker readOnlyChecker = readOnly();
        StoreFactory factory =
                new StoreFactory( databaseLayout, config, idGeneratorFactory, pageCache, fileSystem, logProvider, pageCacheTracer, readOnlyChecker );
        // Don't start the counts stores here as part of life, instead only shut down. This is because it's better to let FullCheck
        // start it and add its missing/broken detection where it can report to user.

        ConsistencySummaryStatistics summary;
        final Path reportFile = chooseReportPath( reportDir );

        Log4jLogProvider reportLogProvider = new Log4jLogProvider(
                LogConfig.createBuilder( fileSystem, reportFile, Level.INFO ).createOnDemand().withCategory( false ).build() );
        Log reportLog = reportLogProvider.getLog( getClass() );
        Log log = new DuplicatingLog( outLog, reportLog );

        // Bootstrap kernel extensions
        Monitors monitors = new Monitors();
        JobScheduler jobScheduler = life.add( JobSchedulerFactory.createInitialisedScheduler() );
        TokenHolders tokenHolders = new TokenHolders( new DelegatingTokenHolder( new ReadOnlyTokenCreator(), TokenHolder.TYPE_PROPERTY_KEY ),
                new DelegatingTokenHolder( new ReadOnlyTokenCreator(), TokenHolder.TYPE_LABEL ),
                new DelegatingTokenHolder( new ReadOnlyTokenCreator(), TokenHolder.TYPE_RELATIONSHIP_TYPE ) );
        final RecoveryCleanupWorkCollector workCollector = RecoveryCleanupWorkCollector.ignore();
        DatabaseExtensions extensions = life.add( instantiateExtensions( databaseLayout,
                fileSystem, config, new SimpleLogService( logProvider ), pageCache, jobScheduler,
                workCollector,
                TOOL, // We use TOOL context because it's true, and also because it uses the 'single' operational mode, which is important.
                monitors, tokenHolders, pageCacheTracer, readOnlyChecker ) );
        DefaultIndexProviderMap indexes = life.add( new DefaultIndexProviderMap( extensions, config ) );

        try ( NeoStores neoStores = factory.openAllNeoStores() )
        {
            long lastCommittedTransactionId = neoStores.getMetaDataStore().getLastCommittedTransactionId();
            CountsStoreManager countsStoreManager = life.add( new CountsStoreManager( pageCache, fileSystem, databaseLayout, pageCacheTracer, memoryTracker,
                    lastCommittedTransactionId ) );
            RelationshipGroupDegreesStoreManager groupDegreesStoreManager = life.add(
                    new RelationshipGroupDegreesStoreManager( pageCache, fileSystem, databaseLayout, pageCacheTracer, memoryTracker,
                            lastCommittedTransactionId, logProvider ) );
            // Load tokens before starting extensions, etc.
            try ( var cursorContext = new CursorContext( pageCacheTracer.createPageCursorTracer( CONSISTENCY_TOKEN_READER_TAG ) ) )
            {
                tokenHolders.setInitialTokens( StoreTokens.allReadableTokens( neoStores ), cursorContext );
            }

            life.start();

            IndexStatisticsStore indexStatisticsStore = new IndexStatisticsStore( pageCache, databaseLayout, workCollector, readOnlyChecker, pageCacheTracer );
            life.add( indexStatisticsStore );

            int numberOfThreads = defaultConsistencyCheckThreadsNumber();
            DirectStoreAccess stores = new DirectStoreAccess( neoStores, indexes, tokenHolders, indexStatisticsStore, idGeneratorFactory );
            double memoryLimitLeewayFactor = config.get( GraphDatabaseInternalSettings.consistency_check_memory_limit_factor );
            FullCheck check = new FullCheck( progressFactory, numberOfThreads, consistencyFlags, config, debugContext,
                    NodeBasedMemoryLimiter.defaultWithLeeway( memoryLimitLeewayFactor ) );
            summary = check.execute( pageCache, stores, countsStoreManager, groupDegreesStoreManager, null, pageCacheTracer, memoryTracker, log );
        }
        finally
        {
            life.shutdown();
            reportLogProvider.close();
        }

        if ( !summary.isConsistent() )
        {
            log.warn( "See '%s' for a detailed consistency report.", reportFile );
            return Result.failure( reportFile, summary );
        }
        return Result.success( reportFile, summary );
    }

    private static void assertRecovered( DatabaseLayout databaseLayout, Config config, FileSystemAbstraction fileSystem, MemoryTracker memoryTracker )
            throws ConsistencyCheckIncompleteException
    {
        try
        {
            if ( isRecoveryRequired( fileSystem, databaseLayout, config, memoryTracker ) )
            {
                throw new IllegalStateException(
                        joinAsLines( "Active logical log detected, this might be a source of inconsistencies.", "Please recover database.",
                                "To perform recovery please start database in single mode and perform clean shutdown." ) );
            }
        }
        catch ( Exception e )
        {
            throw new ConsistencyCheckIncompleteException( e );
        }
    }

    private Path chooseReportPath( Path reportDir )
    {
        return reportDir.resolve( defaultLogFileName( timestamp ) );
    }

    private static Path defaultReportDir( Config tuningConfiguration )
    {
        return tuningConfiguration.get( GraphDatabaseSettings.logs_directory );
    }

    private static String defaultLogFileName( Date date )
    {
        return format( "inconsistencies-%s.report", new SimpleDateFormat( "yyyy-MM-dd.HH.mm.ss" ).format( date ) );
    }

    public static class Result
    {
        private final boolean successful;
        private final Path reportFile;
        private final ConsistencySummaryStatistics summary;

        public static Result failure( Path reportFile, ConsistencySummaryStatistics summary )
        {
            return new Result( false, reportFile, summary );
        }

        public static Result success( Path reportFile, ConsistencySummaryStatistics summary )
        {
            return new Result( true, reportFile, summary );
        }

        private Result( boolean successful, Path reportFile, ConsistencySummaryStatistics summary )
        {
            this.successful = successful;
            this.reportFile = reportFile;
            this.summary = summary;
        }

        public boolean isSuccessful()
        {
            return successful;
        }

        public Path reportFile()
        {
            return reportFile;
        }

        public ConsistencySummaryStatistics summary()
        {
            return summary;
        }
    }

    public static int defaultConsistencyCheckThreadsNumber()
    {
        return Runtime.getRuntime().availableProcessors();
    }

    private static class RebuildPreventingCountsInitializer implements CountsBuilder
    {
        private final long lastCommittedTxId;

        RebuildPreventingCountsInitializer( long lastCommittedTxId )
        {
            this.lastCommittedTxId = lastCommittedTxId;
        }

        @Override
        public void initialize( CountsAccessor.Updater updater, CursorContext cursorContext, MemoryTracker memoryTracker )
        {
            throw new UnsupportedOperationException( "Counts store needed rebuild, consistency checker will instead report broken or missing counts store" );
        }

        @Override
        public long lastCommittedTxId()
        {
            return lastCommittedTxId;
        }
    }

    private static class RebuildPreventingDegreesInitializer implements GBPTreeRelationshipGroupDegreesStore.DegreesRebuilder
    {
        private final long lastCommittedTxId;

        RebuildPreventingDegreesInitializer( long lastCommittedTxId )
        {
            this.lastCommittedTxId = lastCommittedTxId;
        }

        @Override
        public void rebuild( RelationshipGroupDegreesStore.Updater updater, CursorContext cursorContext, MemoryTracker memoryTracker )
        {
            throw new UnsupportedOperationException(
                    "Relationship group degrees store needed rebuild, consistency checker will instead report broken or missing store" );
        }

        @Override
        public long lastCommittedTxId()
        {
            return lastCommittedTxId;
        }
    }

    /**
     * This weird little thing exists because we want to provide {@link CountsStorage} from outside checker, but we want to actually instantiate
     * and start it inside the checker where we have the report instance available. So we pass in something that can supply the store...
     * and it can also close it (we do here in {@link ConsistencyCheckService}.
     */
    private abstract static class CountsStorageManager<T extends CountsStorage> extends LifecycleAdapter implements ThrowingSupplier<T,IOException>
    {
        protected final PageCache pageCache;
        protected final FileSystemAbstraction fileSystem;
        protected final DatabaseLayout databaseLayout;
        protected final PageCacheTracer pageCacheTracer;
        protected final MemoryTracker memoryTracker;
        protected final long lastCommittedTxId;
        private T store;

        CountsStorageManager( PageCache pageCache, FileSystemAbstraction fileSystem, DatabaseLayout databaseLayout, PageCacheTracer pageCacheTracer,
                MemoryTracker memoryTracker, long lastCommittedTxId )
        {
            this.pageCache = pageCache;
            this.fileSystem = fileSystem;
            this.databaseLayout = databaseLayout;
            this.pageCacheTracer = pageCacheTracer;
            this.memoryTracker = memoryTracker;
            this.lastCommittedTxId = lastCommittedTxId;
        }

        @Override
        public T get() throws IOException
        {
            store = open();
            store.start( NULL, memoryTracker );
            return store;
        }

        protected abstract T open() throws IOException;

        @Override
        public void shutdown()
        {
            if ( store != null )
            {
                store.close();
            }
        }
    }

    private static class CountsStoreManager extends CountsStorageManager<CountsStore>
    {
        CountsStoreManager( PageCache pageCache, FileSystemAbstraction fileSystem, DatabaseLayout databaseLayout, PageCacheTracer pageCacheTracer,
                MemoryTracker memoryTracker, long lastCommittedTxId )
        {
            super( pageCache, fileSystem, databaseLayout, pageCacheTracer, memoryTracker, lastCommittedTxId );
        }

        @Override
        protected CountsStore open() throws IOException
        {
            return new GBPTreeCountsStore( pageCache, databaseLayout.countStore(), fileSystem, RecoveryCleanupWorkCollector.ignore(),
                    new RebuildPreventingCountsInitializer( lastCommittedTxId ), readOnly(), pageCacheTracer, GBPTreeCountsStore.NO_MONITOR,
                    databaseLayout.getDatabaseName(), 100 );
        }
    }

    private static class RelationshipGroupDegreesStoreManager extends CountsStorageManager<RelationshipGroupDegreesStore>
    {
        private final LogProvider logProvider;

        RelationshipGroupDegreesStoreManager( PageCache pageCache, FileSystemAbstraction fileSystem, DatabaseLayout databaseLayout,
                PageCacheTracer pageCacheTracer, MemoryTracker memoryTracker, long lastCommittedTxId, LogProvider logProvider )
        {
            super( pageCache, fileSystem, databaseLayout, pageCacheTracer, memoryTracker, lastCommittedTxId );
            this.logProvider = logProvider;
        }

        @Override
        protected RelationshipGroupDegreesStore open() throws IOException
        {
            return new GBPTreeRelationshipGroupDegreesStore( pageCache, databaseLayout.relationshipGroupDegreesStore(), fileSystem,
                    RecoveryCleanupWorkCollector.ignore(), new RebuildPreventingDegreesInitializer( lastCommittedTxId ), readOnly(), pageCacheTracer,
                    GBPTreeCountsStore.NO_MONITOR, databaseLayout.getDatabaseName(), 100, logProvider );
        }
    }
}
