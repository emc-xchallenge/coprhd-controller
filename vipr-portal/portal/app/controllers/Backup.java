/*
 * Copyright (c) 2015 EMC Corporation
 * All Rights Reserved
 */
package controllers;

import static controllers.Common.flashException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;

import models.datatable.BackupDataTable;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.StringUtils;

import play.Logger;
import play.data.binding.As;
import play.data.validation.Required;
import play.data.validation.Valid;
import play.data.validation.Validation;
import play.mvc.Controller;
import play.mvc.With;
import plugin.StorageOsPlugin;
import sun.net.ftp.impl.FtpClient;
import util.BackupUtils;
import util.MessagesUtils;
import util.datatable.DataTablesSupport;

import com.emc.storageos.coordinator.client.service.CoordinatorClient;
import com.emc.storageos.db.client.impl.EncryptionProviderImpl;
import com.emc.storageos.db.client.model.EncryptionProvider;
import com.emc.storageos.management.backup.BackupConstants;
import com.emc.storageos.model.property.PropertyInfo;
import com.emc.vipr.client.exceptions.ViPRException;
import com.emc.vipr.model.sys.backup.BackupSets.BackupSet;
import com.emc.vipr.model.sys.backup.BackupUploadStatus;
import com.google.common.collect.Lists;

import controllers.deadbolt.Restrict;
import controllers.deadbolt.Restrictions;
import controllers.util.FlashException;

/**
 * @author mridhr
 *
 */
@With(Common.class)
@Restrictions({ @Restrict("SYSTEM_ADMIN"), @Restrict("RESTRICTED_SYSTEM_ADMIN") })
public class Backup extends Controller {

    protected static final String SAVED_SUCCESS = "backup.save.success";
    protected static final String DELETED_SUCCESS = "backup.delete.success";
    protected static final String DELETED_ERROR = "backup.delete.error";
    private static CoordinatorClient coordinatorClient = StorageOsPlugin.getInstance().getCoordinatorClient();
    private final static String FTPS_URL_PREFIX = "ftps://";

    public static void list() {
        BackupDataTable dataTable = new BackupDataTable();
        render(dataTable);
    }

    public static void listJson() {
        List<BackupDataTable.Backup> backups = BackupDataTable.fetch();
        renderJSON(DataTablesSupport.createJSON(backups, params));
    }

    public static void itemsJson(@As(",") String[] ids) {
        List<BackupDataTable.Backup> results = Lists.newArrayList();
        if (ids != null && ids.length > 0) {
            for (String id : ids) {
                if (StringUtils.isNotBlank(id)) {
                    BackupSet backup = BackupUtils.getBackup(id);
                    if (backup != null) {
                        results.add(new BackupDataTable.Backup(backup));
                    }
                }
            }
        }
        renderJSON(results);
    }

    public static void create() {
        render();
    }

    public static void cancel() {
        list();
    }

    @FlashException(keep = true, referrer = { "create" })
    public static void save(@Valid BackupForm backupForm) {
        backupForm.validate("name");
        if (Validation.hasErrors()) {
            Common.handleError();
        }
        try {
            backupForm.save();
            flash.success(MessagesUtils.get(SAVED_SUCCESS, backupForm.name));
            backToReferrer();
        } catch (ViPRException e) {
            flashException(e);
            error(backupForm);
        }
    }

    public static void edit(String id) {
        list();
    }

    @FlashException(value = "list")
    public static void delete(@As(",") String[] ids) {
        if (ids != null && ids.length > 0) {
            boolean deleteExecuted = false;
            for (String backupName : ids) {
                BackupUtils.deleteBackup(backupName);
                deleteExecuted = true;
            }
            if (deleteExecuted == true) {
                flash.success(MessagesUtils.get("backups.deleted"));
            }
        }
        list();
    }

    @FlashException(value = "list")
    public static void upload(String id) {
        BackupUtils.uploadBackup(id);
        list();
    }

    @FlashException(value = "list")
    public static void uploadTest() {
        PropertyInfo propInfo = coordinatorClient.getPropertyInfo();
        String urlStr = propInfo.getProperty(BackupConstants.UPLOAD_URL);
        String usernameStr = propInfo.getProperty(BackupConstants.UPLOAD_USERNAME);
        String passwordStr = propInfo.getProperty(BackupConstants.UPLOAD_PASSWD);
        EncryptionProviderImpl provider = new EncryptionProviderImpl();
        provider.setCoordinator(coordinatorClient);
        String password = provider.decrypt(Base64.decodeBase64(passwordStr));
        boolean result = verifyFtp(urlStr, 21, usernameStr, password);
        renderJSON(result);
    }

    private static void backToReferrer() {
        String referrer = Common.getReferrer();
        if (StringUtils.isNotBlank(referrer)) {
            redirect(referrer);
        } else {
            list();
        }
    }

    /**
     * Handles an error while saving a backup form.
     * 
     * @param backupForm
     *            the backup form.
     */
    private static void error(BackupForm backupForm) {
        params.flash();
        Validation.keep();
        create();
    }

    public static class BackupForm {

        @Required
        public String name;

        public boolean force;

        public void validate(String fileName) {
            Validation.valid(fileName, this);
        }

        public void save() throws ViPRException {
            BackupUtils.createBackup(name, force);
        }
    }

    public static boolean verifyFtp(String uri, int port, String username, String password) {
        ProcessBuilder builder = getBuilder(uri, username, password);
        try {
            for (String str : builder.command()) {
                System.out.println(str);
            }
            Process p = builder.start();
            p.destroy();
            Thread.sleep(1000);
            if (p.exitValue() != 0) {
                return false;
            }
        } catch (Exception e) {
            Logger.error("error", uri);
            return false;
        }
        return true;
    }

    private static ProcessBuilder getBuilder(String uri, String username, String password) {
        boolean isExplicit = startsWithIgnoreCase(uri, FTPS_URL_PREFIX);

        ProcessBuilder builder = new ProcessBuilder("curl", uri, "-sSk", "-u", String.format("%s:%s",
                username, password));
        if (!isExplicit) {
            builder.command().add("--ftp-ssl");
        }

        return builder;
    }

    public static boolean startsWithIgnoreCase(String str, String prefix) {
        return str.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    public static void main(String[] args) {
        boolean ret = verifyFtp("ftp://10.247.99.161/test/", 21, "peter", "dangerous");
        System.out.println(ret);
    }
}