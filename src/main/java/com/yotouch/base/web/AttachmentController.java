package com.yotouch.base.web;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.util.DigestUtils;
import org.springframework.util.FileCopyUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

import com.yotouch.core.entity.Entity;
import com.yotouch.core.exception.Four04NotFoundException;
import com.yotouch.core.runtime.DbSession;
import com.yotouch.core.runtime.YotouchApplication;
import com.yotouch.base.service.UploadFileService;

@Controller
public class AttachmentController {
    
    static final private Logger logger = LoggerFactory.getLogger(AttachmentController.class);
    
    @Autowired
    private YotouchApplication ytApp;

    @Autowired
    private UploadFileService uploadFileService;

    @RequestMapping("/admin/attachment/test")
    public String testAttachment() {
        return "admin/testAttachment";
    }
    
    @RequestMapping("/admin/attachment/upload")
    public @ResponseBody Map<String, Object> uploadFile(
            @RequestParam("file") MultipartFile uploadfile
            ) {
        
        String filename = uploadfile.getOriginalFilename();
        
        logger.info("Upload filename " + filename);
        

        Map<String, Object> ret = new HashMap<>();

        ret = uploadFileService.saveAttachment(uploadfile);

        return ret;
    }
    
    @RequestMapping("/attachment/get")
    public void getAttachment(
            @RequestParam(value="uuid") String uuid,
            HttpServletResponse resp
            ) throws IOException {
        
        DbSession dbSession = ytApp.getRuntime().createDbSession();
        Entity att = dbSession.getEntity("attachment", uuid);
        if (att == null) {
            throw new Four04NotFoundException();
        }
        
        
        
        resp.reset();
        
        resp.setContentType(att.getValue("mime"));
        byte[] content = att.getValue("content");
        resp.setContentLength(content.length);

        FileCopyUtils.copy(content, resp.getOutputStream());
        resp.flushBuffer();

        
    }
}
