package org.animallink.identity.domain;

public class CredentialObjectMissingException extends RuntimeException {
    public CredentialObjectMissingException() {
        super("凭证材料对象不存在或尚未上传");
    }
}
