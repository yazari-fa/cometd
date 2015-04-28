/*
 * Copyright (c) 2008-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.cometd.server;

import org.cometd.bayeux.server.ServerMessage;
import org.cometd.common.HashMapMessage;
import org.cometd.common.JSONContext;

import java.util.*;

public class ServerMessageImpl extends HashMapMessage implements ServerMessage.Mutable
{
    private static final long serialVersionUID = 6412048662640296067L;

    private volatile transient ServerMessage.Mutable _associated;
    private volatile boolean _lazy = false;
    private volatile String _json;

    public ServerMessage.Mutable getAssociated()
    {
        return _associated;
    }

    public void setAssociated(ServerMessage.Mutable associated)
    {
        _associated = associated;
    }

    public boolean isLazy()
    {
        return _lazy;
    }

    public void setLazy(boolean lazy)
    {
        _lazy = lazy;
    }

    protected void freeze(String json)
    {
        assert _json == null;
        _json = json;
    }

    protected boolean isFrozen()
    {
        return _json != null;
    }

    @Override
    public String getJSON()
    {
        if (_json == null)
            return _jsonContext.generate(this);
        return _json;
    }

    @Override
    public Object getData()
    {
        Object data = super.getData();
        if (isFrozen() && data instanceof Map)
            return Collections.unmodifiableMap((Map<String, Object>)data);
        return data;
    }

    @Override
    public Object put(String key, Object value)
    {
        if (isFrozen())
            throw new UnsupportedOperationException();
        return super.put(key, value);
    }

    @Override
    public Set<Map.Entry<String, Object>> entrySet()
    {
        if (isFrozen())
            return new ImmutableEntrySet(super.entrySet());
        return super.entrySet();
    }

    @Override
    public Map<String, Object> getDataAsMap()
    {
        Map<String, Object> data = super.getDataAsMap();
        if (isFrozen() && data != null)
            return Collections.unmodifiableMap(data);
        return data;
    }

    @Override
    public Map<String, Object> getExt()
    {
        Map<String, Object> ext = super.getExt();
        if (isFrozen() && ext != null)
            return Collections.unmodifiableMap(ext);
        return ext;
    }

    @Override
    public Map<String, Object> getAdvice()
    {
        Map<String, Object> advice = super.getAdvice();
        if (isFrozen() && advice != null)
            return Collections.unmodifiableMap(advice);
        return advice;
    }

    private static class ImmutableEntrySet extends AbstractSet<Map.Entry<String, Object>>
    {
        private final Set<Map.Entry<String, Object>> delegate;

        private ImmutableEntrySet(Set<Map.Entry<String, Object>> delegate)
        {
            this.delegate = delegate;
        }

        @Override
        public Iterator<Map.Entry<String, Object>> iterator()
        {
            return new ImmutableEntryIterator(delegate.iterator());
        }

        @Override
        public int size()
        {
            return delegate.size();
        }

        private static class ImmutableEntryIterator implements Iterator<Map.Entry<String, Object>>
        {
            private final Iterator<Map.Entry<String, Object>> delegate;

            private ImmutableEntryIterator(Iterator<Map.Entry<String, Object>> delegate)
            {
                this.delegate = delegate;
            }

            public boolean hasNext()
            {
                return delegate.hasNext();
            }

            public Map.Entry<String, Object> next()
            {
                return new ImmutableEntry(delegate.next());
            }

            public void remove()
            {
                throw new UnsupportedOperationException();
            }

            private static class ImmutableEntry implements Map.Entry<String, Object>
            {
                private final Map.Entry<String, Object> delegate;

                private ImmutableEntry(Map.Entry<String, Object> delegate)
                {
                    this.delegate = delegate;
                }

                public String getKey()
                {
                    return delegate.getKey();
                }

                public Object getValue()
                {
                    return delegate.getValue();
                }

                public Object setValue(Object value)
                {
                    throw new UnsupportedOperationException();
                }
            }
        }
    }

    // The code below is a relic of a mistake in the API, but it is kept for backward compatibility

    private static JSONContext.Server _jsonContext = new JettyJSONContextServer();
}
