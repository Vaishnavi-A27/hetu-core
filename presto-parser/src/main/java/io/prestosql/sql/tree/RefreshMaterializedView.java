/*
 * Copyright (C) 2018-2020. Huawei Technologies Co., Ltd. All rights reserved.
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

package io.prestosql.sql.tree;

import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static com.google.common.base.MoreObjects.toStringHelper;

public class RefreshMaterializedView
        extends Statement
{
    private final QualifiedName tableName;
    private final boolean concurrently;

    public RefreshMaterializedView(NodeLocation location, QualifiedName tableName, boolean concurrently)
    {
        this(Optional.of(location), tableName, concurrently);
    }

    private RefreshMaterializedView(Optional<NodeLocation> location, QualifiedName tableName, boolean concurrently)
    {
        super(location);
        this.tableName = tableName;
        this.concurrently = concurrently;
    }

    public QualifiedName getTableName()
    {
        return tableName;
    }

    public boolean isConcurrently()
    {
        return concurrently;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context)
    {
        return visitor.visitRefreshMaterializedView(this, context);
    }

    @Override
    public List<Node> getChildren()
    {
        return ImmutableList.of();
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(tableName, concurrently);
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj) {
            return true;
        }
        if ((obj == null) || (getClass() != obj.getClass())) {
            return false;
        }
        RefreshMaterializedView o = (RefreshMaterializedView) obj;
        return Objects.equals(tableName, o.tableName)
                && (concurrently == o.concurrently);
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("tableName", tableName)
                .add("concurrently", concurrently)
                .toString();
    }
}