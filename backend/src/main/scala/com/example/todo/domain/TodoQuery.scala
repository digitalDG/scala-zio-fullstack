package com.example.todo.domain

enum SortField:
  case CreatedAt, DueDate, Priority, Title

object SortField extends LabeledEnum[SortField]:
  def label(value: SortField): String = value match
    case CreatedAt => "createdAt"
    case DueDate   => "dueDate"
    case Priority  => "priority"
    case Title     => "title"
  protected def typeName = "SortField"

enum SortOrder:
  case Asc, Desc

object SortOrder extends LabeledEnum[SortOrder]:
  def label(value: SortOrder): String = value.toString.toLowerCase
  protected def typeName              = "SortOrder"

final case class TodoFilter(
  completed: Option[Boolean] = None,
  priority: Option[Priority] = None,
  tag: Option[TodoTag] = None,
  search: Option[String] = None
)

final case class PageRequest(page: Int, size: Int):
  def offset: Int = (page - 1) * size

final case class TodoQuery(
  filter: TodoFilter = TodoFilter(),
  sort: SortField = SortField.CreatedAt,
  order: SortOrder = SortOrder.Desc,
  page: PageRequest = PageRequest(1, 20)
)

final case class Page[+A](items: List[A], total: Long, request: PageRequest):
  def map[B](f: A => B): Page[B] = Page(items.map(f), total, request)

  def totalPages: Int =
    if total == 0 then 0 else ((total + request.size - 1) / request.size).toInt
