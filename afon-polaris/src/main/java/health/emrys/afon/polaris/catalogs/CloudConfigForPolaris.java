package health.emrys.afon.polaris.catalogs;

import com.fasterxml.jackson.core.JsonProcessingException;

public abstract class CloudConfigForPolaris {

    private final JsonMaker jsonMaker;

    protected CloudConfigForPolaris(JsonMaker jsonMaker) {
        this.jsonMaker = jsonMaker;
    }


    public String catalogAdminGrants() throws IncorrectConfigException {
        throw new UnsupportedOperationException("TODO");
    }

    public String catalogRoles() throws IncorrectConfigException {
        throw new UnsupportedOperationException("TODO");
    }

    abstract String catalogConfig() throws IncorrectConfigException;

    abstract String warehouseName();

    protected String toJson(Object object) throws IncorrectConfigException {
        try {
            return jsonMaker.toJson(object);
        } catch (JsonProcessingException e) {
            throw new IncorrectConfigException(this.toString(), e);
        }
    }

}
