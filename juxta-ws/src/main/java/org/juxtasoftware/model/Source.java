package org.juxtasoftware.model;

import java.util.Date;
import com.google.common.base.Objects;
import eu.interedition.text.Text;

public class Source extends WorkspaceMember {

    private String name;
    private Text text;
    private Date created;
      
    public final String getName() {
        return name;
    }

    public final void setName(String name) {
        this.name = name;
    }

    public Text getText() {
        return text;
    }

    public void setText(Text content) {
        this.text = content;
    }

    public Date getCreated() {
        return created;
    }

    public void setCreated(Date created) {
        this.created = created;
    }

    @Override
    public int hashCode() {
        return (id == 0 ? super.hashCode() : Objects.hashCode(id));
    }

    @Override
    public boolean equals(Object obj) {
        if (id != 0 && obj != null && obj instanceof Source) {
            return id == ((Source)obj).id;
        }
        return super.equals(obj);
    }

    @Override
    public String toString() {
        return "Source [id=" + id + ", name=" + name + "]";
    }
}
