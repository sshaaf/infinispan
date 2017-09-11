package org.infinispan.xsite.statetransfer;

import static org.infinispan.test.TestingUtil.extractComponent;
import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertTrue;

import java.util.concurrent.TimeUnit;

import org.infinispan.configuration.cache.BackupConfigurationBuilder;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.context.Flag;
import org.infinispan.statetransfer.CommitManager;
import org.infinispan.xsite.AbstractTwoSitesTest;
import org.infinispan.xsite.XSiteAdminOperations;
import org.testng.annotations.Test;

/**
 * Simple test for the state transfer with different cache names.
 *
 * @author Pedro Ruivo
 * @since 7.0
 */
@Test(groups = "xsite", testName = "xsite.statetransfer.BackupForStateTransferTest")
public class BackupForStateTransferTest extends AbstractTwoSitesTest {

   private static final String VALUE = "value";
   private static final String LON_BACKUP_CACHE_NAME = "lonBackup";

   public BackupForStateTransferTest() {
      super();
      this.implicitBackupCache = false;
   }

   public void testStateTransferWithClusterIdle() throws InterruptedException {
      takeSiteOffline(LON, NYC);
      assertOffline(LON, NYC);
      assertNoStateTransferInReceivingSite(NYC, LON_BACKUP_CACHE_NAME);
      assertNoStateTransferInSendingSite(LON);

      //NYC is offline... lets put some initial data in
      //we have 2 nodes in each site and the primary owner sends the state. Lets try to have more key than the chunk
      //size in order to each site to send more than one chunk.
      final int amountOfData = chunkSize(LON) * 4;
      for (int i = 0; i < amountOfData; ++i) {
         cache(LON, 0).put(key(i), VALUE);
      }

      //check if NYC is empty (LON backup cache)
      assertInSite(NYC, LON_BACKUP_CACHE_NAME, cache -> assertTrue(cache.isEmpty()));

      //check if NYC is empty (default cache)
      assertInSite(NYC, cache -> assertTrue(cache.isEmpty()));

      startStateTransfer(LON, NYC);

      eventually(() -> extractComponent(cache(LON, 0), XSiteAdminOperations.class).getRunningStateTransfer().isEmpty(),
            TimeUnit.SECONDS.toMillis(30));

      assertOnline(LON, NYC);

      //check if all data is visible (LON backup cache)
      assertInSite(NYC, LON_BACKUP_CACHE_NAME, cache -> {
         for (int i = 0; i < amountOfData; ++i) {
            assertEquals(VALUE, cache.get(key(i)));
         }
      });

      //check if NYC is empty (default cache)
      assertInSite(NYC, cache -> assertTrue(cache.isEmpty()));

      assertNoStateTransferInReceivingSite(NYC, LON_BACKUP_CACHE_NAME);
      assertNoStateTransferInSendingSite(LON);
   }

   @Override
   protected ConfigurationBuilder getNycActiveConfig() {
      return getDefaultClusteredCacheConfig(CacheMode.DIST_SYNC, false);
   }

   @Override
   protected ConfigurationBuilder getLonActiveConfig() {
      return getDefaultClusteredCacheConfig(CacheMode.DIST_SYNC, false);
   }

   @Override
   protected void adaptLONConfiguration(BackupConfigurationBuilder builder) {
      builder.site(NYC).stateTransfer().chunkSize(10);
   }

   private void startStateTransfer(String fromSite, String toSite) {
      XSiteAdminOperations operations = extractComponent(cache(fromSite, 0), XSiteAdminOperations.class);
      assertEquals(XSiteAdminOperations.SUCCESS, operations.pushState(toSite));
   }

   private void takeSiteOffline(String localSite, String remoteSite) {
      XSiteAdminOperations operations = extractComponent(cache(localSite, 0), XSiteAdminOperations.class);
      assertEquals(XSiteAdminOperations.SUCCESS, operations.takeSiteOffline(remoteSite));
   }

   private void assertOffline(String localSite, String remoteSite) {
      XSiteAdminOperations operations = extractComponent(cache(localSite, 0), XSiteAdminOperations.class);
      assertEquals(XSiteAdminOperations.OFFLINE, operations.siteStatus(remoteSite));
   }

   private void assertOnline(String localSite, String remoteSite) {
      XSiteAdminOperations operations = extractComponent(cache(localSite, 0), XSiteAdminOperations.class);
      assertEquals(XSiteAdminOperations.ONLINE, operations.siteStatus(remoteSite));
   }

   private int chunkSize(String site) {
      return cache(site, 0).getCacheConfiguration().sites().allBackups().get(0).stateTransfer().chunkSize();
   }

   private void assertNoStateTransferInReceivingSite(String siteName, String cacheName) {
      assertEventuallyInSite(siteName, cacheName, cache -> {
         CommitManager commitManager = extractComponent(cache, CommitManager.class);
         return !commitManager.isTracking(Flag.PUT_FOR_STATE_TRANSFER) &&
               !commitManager.isTracking(Flag.PUT_FOR_X_SITE_STATE_TRANSFER) &&
               commitManager.isEmpty();
      }, 30, TimeUnit.SECONDS);
   }

   private void assertNoStateTransferInSendingSite(String siteName) {
      assertInSite(siteName,
            cache -> assertTrue(extractComponent(cache, XSiteStateProvider.class).getCurrentStateSending().isEmpty()));
   }

   private Object key(int index) {
      return "key-" + index;
   }
}
